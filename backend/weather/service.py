import threading
import time

import httpx


class WeatherService:
    def __init__(self, mock=False):
        self.mock = mock
        self.cache = {}
        self.lock = threading.Lock()

    def forecast(self, latitude, longitude):
        if self.mock:
            return {"rain_probability": 75, "mock": True, "available": True}
        key = (round(latitude, 2), round(longitude, 2))
        with self.lock:
            cached = self.cache.get(key)
            if cached and cached[0] > time.monotonic():
                return cached[1]
            try:
                response = httpx.get(
                    "https://api.open-meteo.com/v1/forecast",
                    params={
                        "latitude": key[0],
                        "longitude": key[1],
                        "daily": "precipitation_probability_max",
                        "forecast_days": 1,
                        "timezone": "auto",
                    },
                    timeout=5,
                )
                response.raise_for_status()
                probability = response.json()["daily"]["precipitation_probability_max"][0]
                if not isinstance(probability, (int, float)) or not 0 <= probability <= 100:
                    raise ValueError("Invalid weather")
                result = {"rain_probability": probability, "mock": False, "available": True}
                if len(self.cache) >= 1000:
                    self.cache.clear()
                self.cache[key] = (time.monotonic() + 3600, result)
                return result
            except (httpx.HTTPError, ValueError, KeyError, IndexError):
                return {"rain_probability": None, "mock": False, "available": False}
