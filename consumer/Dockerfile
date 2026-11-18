FROM python:3.12.8-slim
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
RUN useradd --system --uid 10001 --create-home consumer && mkdir /data && chown consumer /data
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY consumer.py .
USER consumer
VOLUME /data
CMD ["python", "consumer.py"]
