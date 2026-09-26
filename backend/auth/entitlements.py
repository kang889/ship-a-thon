from datetime import datetime, timezone
import threading
import time
from urllib.parse import quote

import httpx
from fastapi import HTTPException


class Entitlements:
    def __init__(self, settings):
        self.settings = settings
        self.cache = {}
        self.lock = threading.Lock()

    def require_pro(self, user):
        if self.settings.mode != "PRODUCTION":
            return
        if not self.settings.revenuecat_secret:
            raise HTTPException(503, "Subscription verification is not configured")
        with self.lock:
            cached = self.cache.get(user)
            if cached and cached > time.monotonic():
                return
            try:
                response = httpx.get(
                    f"https://api.revenuecat.com/v1/subscribers/{quote(user, safe='')}",
                    headers={"Authorization": f"Bearer {self.settings.revenuecat_secret}"},
                    timeout=5,
                )
                response.raise_for_status()
                entitlement = response.json()["subscriber"]["entitlements"].get("pro")
                if not entitlement:
                    raise HTTPException(403, "An active Pro subscription is required")
                expires = entitlement.get("expires_date")
                remaining = (
                    300.0
                    if expires is None
                    else (
                        datetime.fromisoformat(expires.replace("Z", "+00:00")) - datetime.now(timezone.utc)
                    ).total_seconds()
                )
                if remaining <= 0:
                    raise HTTPException(403, "Your Pro subscription has expired")
                if len(self.cache) > 1000:
                    self.cache.clear()
                self.cache[user] = time.monotonic() + min(300, remaining)
            except (httpx.HTTPError, ValueError, KeyError, TypeError):
                raise HTTPException(503, "Subscription verification is unavailable") from None
