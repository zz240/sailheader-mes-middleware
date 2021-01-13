
echo "**********************************"
echo "Starting mes-middleware service"
echo "**********************************"
java -Dspring.profiles.active=$PROFILE \
-jar /usr/local/@project.artifactId@/@project.build.finalName@.jar