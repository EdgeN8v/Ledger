from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "sqlite:///./ledger.db"
    jwt_secret: str = "change-me-in-.env"
    jwt_algorithm: str = "HS256"
    access_token_expire_minutes: int = 60 * 24 * 30  # 30 days, family app, low friction
    scheduler_enabled: bool = True     # the daily analysis job (off in tests)
    registration_open: bool = False   # open it only while creating the family accounts
    llm_api_key: str = ""
    llm_provider: str = "none"  # "none" | "anthropic"

    class Config:
        env_file = ".env"


settings = Settings()
