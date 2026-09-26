import hashlib
import threading

from backend.ai.schemas import ExtractionInput, SCHEMAS


class ExtractionService:
    def __init__(self, repository, provider, settings):
        self.repository = repository
        self.provider = provider
        self.settings = settings
        # A single service worker keeps duplicate imports from racing the cache.
        self.lock = threading.Lock()

    def extract(self, user: str, kind: str, request: ExtractionInput):
        content = request.model_dump(mode="json")
        # Timetables are reusable across days; instruction dates can be relative.
        if kind == "timetable":
            content.pop("reference_date")
        import json

        key = hashlib.sha256(
            (self.provider.identity + kind + json.dumps(content, sort_keys=True)).encode()
        ).hexdigest()
        with self.lock:
            cached = self.repository.cached(user, key)
            if cached:
                return {
                    "data": SCHEMAS[kind].model_validate_json(cached).model_dump(mode="json"),
                    "cached": True,
                    "mock": not self.settings.real_ai,
                    "requires_confirmation": True,
                }
            for attempt in range(2):
                self.repository.reserve_call(
                    user, self.settings.daily_user_calls, self.settings.daily_global_calls
                )
                try:
                    result = SCHEMAS[kind].model_validate_json(self.provider.extract(kind, request))
                    break
                except ValueError:
                    if attempt == 1:
                        raise ValueError("Could not read this input. Please enter it manually.") from None
            self.repository.cache(user, key, result.model_dump_json())
            return {
                "data": result.model_dump(mode="json"),
                "cached": False,
                "mock": not self.settings.real_ai,
                "requires_confirmation": True,
            }
