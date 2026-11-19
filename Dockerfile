# VaaniFlow server: one Node.js process and one SQLite file (DATA_DIR=/data).
FROM node:22-bookworm-slim AS build
WORKDIR /app/server
COPY server/package.json server/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY server/ ./
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim
ENV NODE_ENV=production PORT=8787 HOST=0.0.0.0 DATA_DIR=/data
WORKDIR /app/server
RUN groupadd --system vaaniflow && useradd --system --gid vaaniflow --home /app vaaniflow \
  && mkdir -p /data && chown vaaniflow:vaaniflow /data
COPY --from=build --chown=vaaniflow:vaaniflow /app/server/node_modules ./node_modules
COPY --from=build --chown=vaaniflow:vaaniflow /app/server/dist ./dist
COPY --from=build --chown=vaaniflow:vaaniflow /app/server/public ./public
COPY --from=build --chown=vaaniflow:vaaniflow /app/server/package.json ./package.json
USER vaaniflow
VOLUME ["/data"]
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD node -e "fetch('http://127.0.0.1:'+process.env.PORT+'/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "--disable-warning=ExperimentalWarning", "dist/index.js"]
