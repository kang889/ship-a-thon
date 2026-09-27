import base64
import io
import json
from dataclasses import replace

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from backend.ai.direct_model_provider import DirectModelProvider, output_schema
from backend.ai.schemas import ExtractionInput, SCHEMAS
from backend.api.main import create_app
from backend.config import Settings


TIMETABLE = {
    "events": [
        {
            "title": "Systems Lab",
            "course": "CS402",
            "day": "Monday",
            "start_time": "10:00",
            "end_time": "12:00",
            "location": "B12",
        }
    ]
}
INSTRUCTION = {
    "related_event": "Systems Lab",
    "date": "2026-09-28",
    "required_items": ["USB adapter"],
    "tasks": [{"title": "Read chapter 3", "duration": 30}],
}


def completed(data):
    return {
        "status": "completed",
        "output": [
            {"type": "message", "content": [{"type": "output_text", "text": json.dumps({"data": data})}]}
        ],
    }


@pytest.fixture
def wire(monkeypatch):
    requests = []
    responses = []
    original = httpx.Client

    def handle(request):
        requests.append(request)
        result = responses.pop(0)
        if isinstance(result, Exception):
            raise result
        status, payload = result
        return httpx.Response(status, json=payload)

    monkeypatch.setattr(
        httpx, "Client", lambda **kwargs: original(**kwargs, transport=httpx.MockTransport(handle))
    )
    return requests, responses


@pytest.mark.parametrize("kind,data", [("timetable", TIMETABLE), ("instruction", INSTRUCTION)])
def test_text_contract(wire, kind, data):
    requests, responses = wire
    responses.append((200, completed(data)))
    result = DirectModelProvider("test-secret", "gpt-4.1-mini").extract(
        kind, ExtractionInput(text="Lecturer source text", reference_date="2026-09-27")
    )
    assert json.loads(result) == data
    request = requests[0]
    assert str(request.url) == "https://api.openai.com/v1/responses"
    assert request.headers["Authorization"] == "Bearer test-secret"
    body = json.loads(request.content)
    assert body["store"] is False and body["max_output_tokens"] == 2048
    assert body["input"][0]["content"] == [{"type": "input_text", "text": "Lecturer source text"}]
    assert body["text"]["format"]["strict"] is True
    assert ("2026-09-27" in body["instructions"]) == (kind == "instruction")
    assert "test-secret" not in request.content.decode()


def test_screenshot_contract(wire):
    requests, responses = wire
    responses.append((200, completed(TIMETABLE)))
    image = io.BytesIO()
    Image.new("RGB", (50, 50), "white").save(image, format="PNG")
    source = ExtractionInput(
        image_base64=base64.b64encode(image.getvalue()).decode(), reference_date="2026-09-27"
    )
    DirectModelProvider("test-secret", "gpt-4.1-mini").extract("timetable", source)
    part = json.loads(requests[0].content)["input"][0]["content"][0]
    assert part["type"] == "input_image"
    assert part["detail"] == "high"
    assert part["image_url"] == f"data:image/jpeg;base64,{source.image_base64}"


@pytest.mark.parametrize("kind", ["timetable", "instruction"])
def test_strict_schema_does_not_mutate_models(kind):
    before = SCHEMAS[kind].model_json_schema()
    schema = output_schema(kind)

    def walk(node):
        if isinstance(node, dict):
            assert "default" not in node
            if node.get("type") == "object":
                assert node["additionalProperties"] is False
                assert set(node["required"]) == set(node["properties"])
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(schema)
    assert SCHEMAS[kind].model_json_schema() == before


@pytest.mark.parametrize(
    "payload",
    [
        {"status": "incomplete", "output": []},
        {"status": "completed", "output": []},
        {"status": "completed", "output": [{"type": "message", "content": [{"type": "refusal"}]}]},
        completed(None),
        completed({"events": []}),
        {
            "status": "completed",
            "output": [{"type": "message", "content": [{"type": "output_text", "text": "invalid JSON"}]}],
        },
        {},
    ],
)
def test_invalid_output_never_accepted(wire, payload):
    _, responses = wire
    responses.append((200, payload))
    with pytest.raises(ValueError):
        DirectModelProvider("test-secret", "gpt-4.1-mini").extract(
            "timetable", ExtractionInput(text="source", reference_date="2026-09-27")
        )


@pytest.mark.parametrize(
    "failure",
    [(401, {"error": "invalid key"}), (429, {"error": "quota"}), (500, {}), httpx.ReadTimeout("timeout")],
)
def test_http_errors_not_retried_and_secrets_not_exposed(wire, tmp_path, failure):
    requests, responses = wire
    responses.append(failure)
    settings = Settings(
        mode="LOCAL",
        real_ai=True,
        api_key="test-secret",
        memory_mode="lexical",
        database_url=f"sqlite:///{tmp_path / 'errors.db'}",
    )
    with TestClient(create_app(settings)) as client:
        result = client.post(
            "/api/v1/ai/timetable/extract",
            headers={"Authorization": "Bearer local-development-only"},
            json={"text": "source", "reference_date": "2026-09-27"},
        )
    assert result.status_code == 502
    assert "test-secret" not in result.text
    assert len(requests) == 1


def test_live_provider_wiring_cache_confirmation_and_quota(wire, tmp_path):
    requests, responses = wire
    responses.append((200, completed(INSTRUCTION)))
    settings = Settings(
        mode="LOCAL",
        real_ai=True,
        api_key="test-secret",
        memory_mode="lexical",
        daily_user_calls=1,
        database_url=f"sqlite:///{tmp_path / 'flow.db'}",
    )
    with TestClient(create_app(settings)) as client:
        client.headers["Authorization"] = "Bearer local-development-only"
        body = {
            "text": "Bring USB adapter Monday and read chapter 3 for 30 minutes.",
            "reference_date": "2026-09-27",
        }
        first = client.post("/api/v1/ai/instruction/extract", json=body)
        assert first.status_code == 200
        assert first.json()["data"] == INSTRUCTION
        assert not first.json()["mock"] and first.json()["requires_confirmation"]
        assert client.post("/api/v1/ai/instruction/extract", json=body).json()["cached"]
        assert client.get("/api/v1/events").json() == []
        body["text"] += " new"
        assert client.post("/api/v1/ai/instruction/extract", json=body).status_code == 429
        assert client.post("/api/v1/memory/search", json={"query": "adapter"}).json()["mock"]
    assert len(requests) == 1


def test_settings_key_opt_in_and_memory_guard(monkeypatch):
    monkeypatch.setenv("APP_MODE", "LOCAL")
    monkeypatch.setenv("ENABLE_PAID_AI", "true")
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    with pytest.raises(ValueError, match="OPENAI_API_KEY"):
        Settings.from_env()
    monkeypatch.setenv("OPENAI_API_KEY", "test-secret")
    monkeypatch.setenv("MEMORY_MODE", "lexical")
    assert Settings.from_env().api_key == "test-secret"
    monkeypatch.setenv("APP_MODE", "MOCK")
    with pytest.raises(ValueError):
        Settings.from_env()
    monkeypatch.setenv("APP_MODE", "PRODUCTION")
    # Production still requires PostgreSQL and Firebase, so an unconfigured host is rejected.
    with pytest.raises(ValueError, match="Production requires"):
        Settings.from_env()
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://u:p@host:5432/db")
    monkeypatch.setenv("FIREBASE_PROJECT_ID", "proj")
    # The prototype deployment runs production with lexical memory and no Qdrant.
    assert Settings.from_env().memory_mode == "lexical"
    # Semantic memory in production still requires Qdrant credentials.
    monkeypatch.setenv("MEMORY_MODE", "semantic")
    with pytest.raises(ValueError, match="[Ss]emantic memory"):
        Settings.from_env()


def test_mock_requires_no_key_or_external_request(wire, tmp_path):
    requests, _ = wire
    settings = replace(Settings(), database_url=f"sqlite:///{tmp_path / 'mock.db'}")
    with TestClient(create_app(settings)) as client:
        result = client.post(
            "/api/v1/ai/timetable/extract",
            headers={"Authorization": "Bearer local-development-only"},
            json={"text": "source", "reference_date": "2026-09-27"},
        )
    assert result.status_code == 200 and result.json()["mock"]
    assert not requests
