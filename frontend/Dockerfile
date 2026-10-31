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
# Root olmayan nginx (SEC-11/INF-07): imajdaki "nginx" kullanicisi (uid 101). Ayricalikli port
# gerekmesin diye 8080 dinlenir; pid ve gecici dosyalar /tmp'de (compose/K8s'te read-only kok +
# tmpfs ile calisabilsin). "user" yonergesi root olmayan master'da anlamsiz, uyari vermesin diye silinir.
RUN sed -i -e '/^user /d' -e 's,^pid .*,pid /tmp/nginx.pid;,' /etc/nginx/nginx.conf \
    && sed -i '/^http {/a \    client_body_temp_path /tmp/client_temp;\n    proxy_temp_path /tmp/proxy_temp;\n    fastcgi_temp_path /tmp/fastcgi_temp;\n    uwsgi_temp_path /tmp/uwsgi_temp;\n    scgi_temp_path /tmp/scgi_temp;' /etc/nginx/nginx.conf \
    && chown -R nginx:nginx /var/cache/nginx
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
