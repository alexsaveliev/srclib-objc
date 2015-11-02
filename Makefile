ifeq ($(OS),Windows_NT)
	GRADLEW = .\gradlew.bat
else
	GRADLEW = ./gradlew
endif

SRC = $(shell /usr/bin/find ./src -type f)

.PHONY: default install test test-gen clean dist

default: install

build/libs/srclib-objc-0.0.1-SNAPSHOT.jar: build.gradle ${SRC}
	${GRADLEW} jar

.bin/srclib-objc.jar: build/libs/srclib-objc-0.0.1-SNAPSHOT.jar
	cp build/libs/srclib-objc-0.0.1-SNAPSHOT.jar .bin/srclib-objc.jar

install: .bin/srclib-objc.jar

test: .bin/srclib-objc.jar
	src -v test -m program

test-gen: .bin/srclib-objc.jar
	src -v test -m program --gen

clean:
	rm -f .bin/srclib-objc.jar
	rm -rf build


dist:
	echo hello