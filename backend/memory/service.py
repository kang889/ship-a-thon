import hashlib
import math
from uuid import NAMESPACE_URL, uuid5


class FakeMemory:
    """Explicit MOCK lexical retrieval, never presented as semantic AI."""

    def __init__(self, repository):
        self.repository = repository

    def put(self, user, memory):
        self.repository.put(user, "memory", memory.id, memory.model_dump_json())

    def search(self, user, query, course, limit):
        terms = set(query.lower().split())
        results = [
            item for item in self.repository.list(user, "memory") if not course or item["course"] == course
        ]
        return sorted(results, key=lambda item: len(terms & set(item["text"].lower().split())), reverse=True)[
            :limit
        ]

    def delete(self, user, key):
        self.repository.delete(user, "memory", key)


class SemanticMemory:
    def __init__(self, repository, settings):
        from qdrant_client import QdrantClient, models
        from sentence_transformers import SentenceTransformer

        self.repository = repository
        self.models = models
        self.encoder = SentenceTransformer(
            "sentence-transformers/all-MiniLM-L6-v2", revision="c9745ed1d9f207416be6d2e6f8de32d1f16199bf"
        )
        self.client = QdrantClient(url=settings.qdrant_url, api_key=settings.qdrant_key or None, timeout=10)
        self.collection = "student_memories_v1"
        if not self.client.collection_exists(self.collection):
            self.client.create_collection(
                self.collection, vectors_config=models.VectorParams(size=384, distance=models.Distance.COSINE)
            )
            self.client.create_payload_index(self.collection, "user_id", models.PayloadSchemaType.KEYWORD)

    def point_id(self, user, key):
        return str(uuid5(NAMESPACE_URL, f"{len(user)}:{user}:{key}"))

    def put(self, user, memory):
        point_id = self.point_id(user, memory.id)
        content_hash = hashlib.sha256(memory.text.encode()).hexdigest()
        existing = self.client.retrieve(self.collection, [point_id], with_payload=True, with_vectors=True)
        # Compare the durable vector payload, not a metadata write that might precede a failed upsert.
        if existing and existing[0].payload.get("content_hash") == content_hash:
            vector = existing[0].vector
        else:
            vector = self.encoder.encode(memory.text, normalize_embeddings=True).tolist()
        if not all(math.isfinite(value) for value in vector):
            raise ValueError("Invalid embedding")
        self.client.upsert(
            self.collection,
            [
                self.models.PointStruct(
                    id=point_id,
                    vector=vector,
                    payload={**memory.model_dump(mode="json"), "user_id": user, "content_hash": content_hash},
                )
            ],
            wait=True,
        )
        self.repository.put(user, "memory", memory.id, memory.model_dump_json())

    def search(self, user, query, course, limit):
        filters = [self.models.FieldCondition(key="user_id", match=self.models.MatchValue(value=user))]
        if course:
            filters.append(
                self.models.FieldCondition(key="course", match=self.models.MatchValue(value=course))
            )
        # Only the query is embedded; stored memories are not re-embedded on search.
        result = self.client.query_points(
            self.collection,
            query=self.encoder.encode(query, normalize_embeddings=True).tolist(),
            query_filter=self.models.Filter(must=filters),
            limit=limit,
            with_payload=True,
        )
        return [
            {key: value for key, value in point.payload.items() if key not in {"user_id", "content_hash"}}
            for point in result.points
        ]

    def delete(self, user, key):
        self.client.delete(
            self.collection, self.models.PointIdsList(points=[self.point_id(user, key)]), wait=True
        )
        self.repository.delete(user, "memory", key)
