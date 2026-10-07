"""
Turns free text (voice transcript, a WeChat/Alipay payment notification, a bank SMS)
into a structured transaction guess: amount + category + note.

Two tiers:
  1. Rule-based (regex amount + keyword category matching) - always available, zero cost.
  2. LLM assist (optional) - only used when settings.llm_provider != "none". Swap in your
     own call in `_llm_parse` once you have an API key; until then the rule-based tier
     handles everything so the app is never blocked on it.

Amount accuracy is the priority (per product decision: category/note can be fixed later,
a wrong amount is the one thing that has to be right), so any failure to find a number
is surfaced as an error rather than silently guessing 0.
"""
import re
from decimal import Decimal, InvalidOperation
from typing import Optional

from sqlalchemy.orm import Session

from app import models
from app.config import settings

AMOUNT_PATTERN = re.compile(r"(\d+(?:\.\d{1,2})?)\s*(?:元|块|¥)?")

# words that hint the amount found is a balance/limit, not the transaction amount itself
NOISE_CONTEXT = ["余额", "可用额度", "剩余"]


class ParseError(Exception):
    pass


def extract_amount(text: str) -> Decimal:
    candidates = []
    for match in AMOUNT_PATTERN.finditer(text):
        start = match.start()
        window = text[max(0, start - 4):start]
        if any(noise in window for noise in NOISE_CONTEXT):
            continue
        try:
            candidates.append(Decimal(match.group(1)))
        except InvalidOperation:
            continue

    if not candidates:
        raise ParseError("no_amount_found")

    # multiple numbers (e.g. "35" and a date) -> assume the largest plausible money-looking one wins,
    # this is a heuristic and will be reviewed via needs_review when confidence is low elsewhere.
    return max(candidates)


def match_category(text: str, categories: list[models.Category]) -> Optional[models.Category]:
    for category in categories:
        if not category.keywords:
            continue
        for kw in category.keywords.split(","):
            kw = kw.strip()
            if kw and kw in text:
                return category
    return None


def _llm_parse(text: str, categories: list[models.Category]) -> Optional[dict]:
    if settings.llm_provider == "none" or not settings.llm_api_key:
        return None
    # TODO(phase 3): call the configured LLM provider here to disambiguate category / clean up
    # `note` for cases the keyword matcher misses. Keep extract_amount() as the source of truth
    # for the amount even when this is wired up - the LLM should only fill category + note.
    return None


def parse_transaction(
    db: Session, ledger_id: int, text: str
) -> tuple[Decimal, Optional[models.Category], str, bool]:
    """Returns (amount, category_or_None, note, needs_review)."""
    amount = extract_amount(text)

    categories = db.query(models.Category).filter(models.Category.ledger_id == ledger_id).all()
    llm_result = _llm_parse(text, categories)

    if llm_result:
        return amount, llm_result.get("category"), llm_result.get("note", text), False

    category = match_category(text, categories)
    needs_review = category is None
    return amount, category, text.strip(), needs_review
