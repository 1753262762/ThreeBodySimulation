FROM node:22-bookworm-slim AS frontend-build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY contracts/ /workspace/contracts/
COPY frontend/ ./
RUN npm run build


FROM maven:3.9-eclipse-temurin-17 AS backend-build

WORKDIR /workspace

COPY . ./
COPY --from=frontend-build /workspace/frontend/dist ./frontend/dist

RUN mvn -B -ntp -DskipTests -Dexec.skip=true -pl simulation-launcher -am package


FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --gid 10001 threebody \
    && useradd --uid 10001 --gid threebody --create-home \
        --home-dir /home/threebody --shell /usr/sbin/nologin threebody \
    && mkdir -p /app /home/threebody/.threebody-lab \
    && chown -R threebody:threebody /app /home/threebody

WORKDIR /app

COPY --from=backend-build --chown=threebody:threebody \
    /workspace/simulation-launcher/target/three-body-lab.jar ./three-body-lab.jar

USER 10001:10001

ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true"

EXPOSE 8721

ENTRYPOINT ["java", "-jar", "/app/three-body-lab.jar"]
