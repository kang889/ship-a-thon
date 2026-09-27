import threading
import time
import math
from datetime import datetime

import httpx

from backend.weather.providers import MockWeatherProvider, OpenMeteoWeatherProvider


class WeatherService:
    """Cache and validate one stable forecast contract regardless of provider."""

    def __init__(self, mock=False, provider=None):
        self.provider = provider or (MockWeatherProvider() if mock else OpenMeteoWeatherProvider())
        self.cache = {}
        self.lock = threading.Lock()

    def forecast(self, latitude, longitude):
        key = (round(latitude, 2), round(longitude, 2))
        with self.lock:
            cached = self.cache.get(key)
            if cached and cached[0] > time.monotonic():
                return cached[1]

        try:
            result = self.provider.forecast(*key)
            if not isinstance(result.get("mock"), bool):
                raise ValueError("Invalid provider metadata")
            hours = result["hourly"]
            if not isinstance(hours, list) or not hours:
                raise ValueError("Missing hourly forecast")
            normalized_hours = []
            for hour in hours:
                stamp = hour["time"]
                if not isinstance(stamp, str) or not stamp.endswith("Z"):
                    raise ValueError("Forecast must use UTC ISO timestamps")
                datetime.fromisoformat(stamp.replace("Z", "+00:00"))
                probability = hour["rain_probability"]
                if type(probability) not in (int, float) or not math.isfinite(probability) or not 0 <= probability <= 100:
                    raise ValueError("Invalid rain probability")
                if hour["temperature"] is not None and type(hour["temperature"]) not in (int, float):
                    raise ValueError("Invalid temperature")
                if hour["weather_code"] is not None and type(hour["weather_code"]) is not int:
                    raise ValueError("Invalid weather code")
                # The native contract uses whole percentages; rounding up preserves >60 semantics.
                normalized_hours.append({**hour, "rain_probability": math.ceil(probability)})
            result = {
                "available": True,
                "mock": result["mock"],
                "hourly": normalized_hours,
                # Informational only: product decisions use the hourly forecast.
                "rain_probability": max(hour["rain_probability"] for hour in hours),
            }
            with self.lock:
                if len(self.cache) >= 1000:
                    self.cache.clear()
                self.cache[key] = (time.monotonic() + 3600, result)
            return result
        except (httpx.HTTPError, ValueError, KeyError, IndexError, TypeError, OverflowError):
            return {"available": False, "mock": False, "hourly": [], "rain_probability": None}
