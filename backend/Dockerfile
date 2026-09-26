FROM python:3.12.10-slim
WORKDIR /app
COPY backend/requirements*.txt /app/backend/
ARG ONLINE_DEPENDENCIES=false
RUN if [ "$ONLINE_DEPENDENCIES" = "true" ]; then pip install --no-cache-dir -r backend/requirements-online.txt; else pip install --no-cache-dir -r backend/requirements.txt; fi
COPY backend /app/backend
RUN useradd --create-home student && chown -R student:student /app
USER student
EXPOSE 8000
CMD ["uvicorn", "backend.api.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
