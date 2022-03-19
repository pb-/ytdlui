develop: data
	STORAGE_PATH=data clojure -M:nrepl
.PHONY: develop

data:
	mkdir -p data/downloads
	sqlite3 data/db < resources/schema.sql

run: data
	STORAGE_PATH=data clojure -M -m ytdlui.core
.PHONY: run

image:
	docker build --build-arg version=$(shell git describe --dirty --always) -t ytdlui .
.PHONY: image

publish:
	docker tag ytdlui pbgh/ytdlui
	docker push pbgh/ytdlui
.PHONY: publish
