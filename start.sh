#!/bin/bash

LIB_DIR=lib
MAIN_CLASS=org.homevision.Application
MAIN_JAR=home-vision-0.1.jar
CLASSPATH=$MAIN_JAR:$(JARS=("$LIB_DIR"/*.jar); IFS=:; echo "${JARS[*]}")

java -Djava.library.path=$LIB_DIR -cp "$CLASSPATH" $MAIN_CLASS
