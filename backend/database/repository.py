from datetime import datetime, timezone

from sqlalchemy import Column, Integer, MetaData, String, Table, Text, and_, create_engine, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.dialects.sqlite import insert as sqlite_insert


metadata = MetaData()
records = Table(
    "records",
    metadata,
    Column("user_id", String(128), primary_key=True),
    Column("kind", String(32), primary_key=True),
    Column("id", String(128), primary_key=True),
    Column("body", Text, nullable=False),
)
cache = Table(
    "ai_cache",
    metadata,
    Column("user_id", String(128), primary_key=True),
    Column("content_hash", String(64), primary_key=True),
    Column("body", Text, nullable=False),
)
usage = Table(
    "ai_daily_usage",
    metadata,
    Column("scope", String(160), primary_key=True),
    Column("day", String(10), primary_key=True),
    Column("calls", Integer, nullable=False, default=0),
)


class QuotaExceeded(Exception):
    pass


class Repository:
    def __init__(self, url: str):
        # SQLAlchemy maps the bare "postgresql://" scheme to the psycopg2 driver, which this
        # project does not install. Select the installed Psycopg 3 driver by normalising only
        # that scheme prefix; explicit drivers (e.g. postgresql+psycopg://) and other URLs are
        # left untouched. Only the scheme is rewritten, so credentials are never inspected.
        if url.startswith("postgresql://"):
            url = "postgresql+psycopg://" + url[len("postgresql://") :]
        self.engine = create_engine(
            url, connect_args={"check_same_thread": False} if url.startswith("sqlite") else {}
        )
        metadata.create_all(self.engine)

    def upsert(self, table, values, keys, connection):
        factory = sqlite_insert if self.engine.dialect.name == "sqlite" else pg_insert
        stmt = factory(table).values(**values)
        connection.execute(
            stmt.on_conflict_do_update(
                index_elements=keys, set_={key: value for key, value in values.items() if key not in keys}
            )
        )

    def cached(self, user: str, key: str):
        with self.engine.connect() as connection:
            return connection.execute(
                select(cache.c.body).where(cache.c.user_id == user, cache.c.content_hash == key)
            ).scalar()

    def cache(self, user: str, key: str, body: str):
        with self.engine.begin() as connection:
            self.upsert(
                cache,
                {"user_id": user, "content_hash": key, "body": body},
                ["user_id", "content_hash"],
                connection,
            )

    def reserve_call(self, user: str, user_limit: int, global_limit: int):
        day = datetime.now(timezone.utc).date().isoformat()
        factory = sqlite_insert if self.engine.dialect.name == "sqlite" else pg_insert
        with self.engine.begin() as connection:
            # Each physical attempt is reserved before contacting the provider. Atomic
            # conditional updates also bound cost across processes and retry failures.
            for scope, limit in (("global", global_limit), (f"user:{user}", user_limit)):
                connection.execute(
                    factory(usage).values(scope=scope, day=day, calls=0).on_conflict_do_nothing()
                )
                result = connection.execute(
                    update(usage)
                    .where(usage.c.scope == scope, usage.c.day == day, usage.c.calls < limit)
                    .values(calls=usage.c.calls + 1)
                )
                if result.rowcount != 1:
                    raise QuotaExceeded("Today's extraction limit is reached. Use manual entry.")

    def list(self, user: str, kind: str):
        import json

        with self.engine.connect() as connection:
            return [
                json.loads(row[0])
                for row in connection.execute(
                    select(records.c.body).where(records.c.user_id == user, records.c.kind == kind)
                )
            ]

    def put(self, user: str, kind: str, key: str, body: str):
        with self.engine.begin() as connection:
            self.upsert(
                records,
                {"user_id": user, "kind": kind, "id": key, "body": body},
                ["user_id", "kind", "id"],
                connection,
            )

    def delete(self, user: str, kind: str, key: str):
        with self.engine.begin() as connection:
            connection.execute(
                records.delete().where(
                    and_(records.c.user_id == user, records.c.kind == kind, records.c.id == key)
                )
            )

    def put_events(self, user, events):
        with self.engine.begin() as connection:
            for event in events:
                self.upsert(
                    records,
                    {"user_id": user, "kind": "event", "id": event.id, "body": event.model_dump_json()},
                    ["user_id", "kind", "id"],
                    connection,
                )
