# 前端镜像：nginx 托管 01 打包的 dist（build.ps1 复制而来）+ /api/v1 反代后端
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html
EXPOSE 80
