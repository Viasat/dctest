FROM node:16 as build

RUN apt-get -y update && \
    apt-get -y install default-jdk-headless

# Separate npm and clojure deps from main app build
RUN mkdir -p /app
ADD shadow-cljs.edn package.json package-lock.json /app/
RUN cd /app && npm --unsafe-perm install
RUN cd /app && ./node_modules/.bin/shadow-cljs info

# main app build
ADD src/ /app/src/
ADD test/ /app/test/
RUN cd /app && \
    ./node_modules/.bin/shadow-cljs compile test && \
    node build/test.js
RUN cd /app && \
    ./node_modules/.bin/shadow-cljs compile dctest && \
    chmod +x build/*.js


FROM node:16-slim as run

COPY --from=build /app/ /app/
ADD schema.yaml /app/

ENTRYPOINT ["/app/build/dctest.js"]
WORKDIR /app
