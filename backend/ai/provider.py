from typing import Protocol

from backend.ai.schemas import ExtractionInput


class IAiExtractionProvider(Protocol):
    identity: str

    def extract(self, kind: str, request: ExtractionInput) -> str: ...


class FakeProvider:
    identity = "fake-v1"

    def extract(self, kind: str, request: ExtractionInput) -> str:
        import json

        if kind == "timetable":
            return json.dumps(
                {
                    "events": [
                        {
                            "title": "Programming Lab (demo)",
                            "course": "CS101",
                            "day": "Monday",
                            "start_time": "14:00",
                            "end_time": "16:00",
                            "location": "Room 3-5",
                        }
                    ]
                }
            )
        return json.dumps(
            {
                "related_event": "Math Tutorial (demo)",
                "date": request.reference_date.isoformat(),
                "required_items": ["Scientific calculator"],
                "tasks": [{"title": "Complete Questions 1–8", "duration": 60}],
            }
        )
