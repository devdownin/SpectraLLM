# Build stage
#
# `--platform=$BUILDPLATFORM` : ce stage tourne sur l'architecture du CONSTRUCTEUR. Il ne
# produit que des fichiers statiques (JS, CSS, HTML), identiques quelle que soit
# l'architecture visée. Sans cette précision, la construction multi-architecture qui publie
# l'image (.github/workflows/docker-publish.yml) rejouerait `npm ci` + le build Vite sous
# émulation QEMU pour un résultat rigoureusement identique. Le stage Nginx ci-dessous
# reste bâti pour l'architecture cible.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Serve stage
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
