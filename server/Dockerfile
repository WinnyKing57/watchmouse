FROM python:3.11-alpine

WORKDIR /app

COPY app.py /app/app.py

RUN mkdir -p /data

ENV PORT=8080

EXPOSE 8080

VOLUME ["/data"]

CMD ["python", "app.py"]