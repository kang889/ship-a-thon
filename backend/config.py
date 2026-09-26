import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    mode: str = "MOCK"
    database_url: str = "sqlite:///./student-memory.db"
    real_ai: bool = False
    api_key: str = ""
    model: str = "gemini-2.5-flash-lite"
    daily_user_calls: int = 5
    daily_global_calls: int = 50
    dev_token: str = "local-development-only"
    qdrant_url: str = "http://localhost:6333"
    qdrant_key: str = ""
    firebase_project: str = ""
    revenuecat_secret: str = ""

    @classmethod
    def from_env(cls):
        mode = os.getenv("APP_MODE", "MOCK")
        if mode not in {"MOCK", "LOCAL", "PRODUCTION"}:
            raise ValueError("APP_MODE must be MOCK, LOCAL or PRODUCTION")
        settings = cls(
            mode=mode,
            database_url=os.getenv("DATABASE_URL", "sqlite:///./student-memory.db"),
            real_ai=os.getenv("ENABLE_PAID_AI", "false").lower() == "true",
            api_key=os.getenv("GEMINI_API_KEY", ""),
            model=os.getenv("AI_MODEL", "gemini-2.5-flash-lite"),
            daily_user_calls=int(os.getenv("DAILY_USER_AI_CALLS", "5")),
            daily_global_calls=int(os.getenv("DAILY_GLOBAL_AI_CALLS", "50")),
            dev_token=os.getenv("DEV_TOKEN", "local-development-only"),
            qdrant_url=os.getenv("QDRANT_URL", "http://localhost:6333"),
            qdrant_key=os.getenv("QDRANT_API_KEY", ""),
            firebase_project=os.getenv("FIREBASE_PROJECT_ID", ""),
            revenuecat_secret=os.getenv("REVENUECAT_SECRET_KEY", ""),
        )
        if settings.real_ai and (not settings.api_key or mode == "MOCK"):
            raise ValueError("Paid AI requires LOCAL/PRODUCTION mode and a server API key")
        if settings.daily_user_calls < 1 or settings.daily_global_calls < 1:
            raise ValueError("AI limits must be positive")
        if mode == "PRODUCTION" and (
            not settings.database_url.startswith("postgresql")
            or not settings.firebase_project
            or not settings.real_ai
            or not settings.qdrant_key
        ):
            raise ValueError(
                "Production requires PostgreSQL, Firebase, paid AI opt-in and Qdrant credentials"
            )
        return settings
