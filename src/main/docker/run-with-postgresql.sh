#!/usr/bin/env bash
echo "**********************************"
echo "Starting mes-middleware service"
echo "**********************************"
# 执行 jar
su postgres -c postgres & java -Dspring.profiles.active=$PROFILE \
-jar /usr/local/@project.artifactId@/@project.build.finalName@.jar && fg
