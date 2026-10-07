"""All timestamps in the ledger are naive China-local time (UTC+8): it is a family app used in one
timezone, and month/year summaries must roll over at local midnight, not UTC midnight."""
from datetime import datetime, timedelta, timezone

CN = timezone(timedelta(hours=8))


def now_local() -> datetime:
    return datetime.now(CN).replace(tzinfo=None)


def from_epoch_ms(ms: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000, CN).replace(tzinfo=None)
