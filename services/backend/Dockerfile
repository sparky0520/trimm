FROM node:22-alpine

ENV NODE_ENV=production
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts

COPY services/backend/server ./services/backend/server

USER node
EXPOSE 8787
CMD ["node", "services/backend/server/server.cjs"]
