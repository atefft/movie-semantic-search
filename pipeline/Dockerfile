FROM python:3.11-slim

WORKDIR /app

# libgomp1 required by torch CPU wheels on slim images
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy pipeline scripts; tests are excluded
COPY *.py ./
