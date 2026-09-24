FROM python:3.11-alpine

WORKDIR /app

COPY app.py /app/app.py

RUN mkdir -p /data

ENV PORT=8080

EXPOSE 8080

VOLUME ["/data"]

HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:${PORT}/healthz || exit 1

CMD ["python", "app.py"]