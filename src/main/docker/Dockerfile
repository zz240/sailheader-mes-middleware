FROM openjdk:8-jdk-alpine
MAINTAINER steven.xu <steven.xu@sailheader.com> 2021-01-05
# 更新源地址为阿里云
RUN cp /etc/apk/repositories /etc/apk/repositories.bak && sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories && apk update
# 更新时区为上海
RUN apk add -U tzdata && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && apk del tzdata
# 导入容器启动时的脚本
RUN mkdir -p /usr/local/@project.artifactId@
COPY @project.build.finalName@.jar /usr/local/@project.artifactId@/
COPY run.sh run.sh
RUN chmod +x run.sh
CMD ./run.sh