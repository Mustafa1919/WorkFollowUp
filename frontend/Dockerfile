# --- Build stage -----------------------------------------------------------
FROM node:22.23.4-alpine3.24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# API ayni origin'den (nginx proxy) cagrilir; refresh cookie'si boylece gonderilir.
ENV VITE_API_BASE_URL=
RUN npm run build

# --- Run stage ---------------------------------------------------------------
FROM nginx:1.30.6-alpine3.24
# Taban imaj Alpine yamalarini gec aliyor (Trivy: brotli-libs CVE-2026-111497, curl CVE-2026-92392);
# imaj yeniden kurulurken guncel paketleri al.
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
