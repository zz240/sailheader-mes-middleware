
-- --------------------------------------------------
-- 创建用户赋予权限
-- --------------------------------------------------
\c middleware;
CREATE USER middleware_user WITH PASSWORD '860fbbdd75de0b5d517323d6f1832e10';
ALTER DATABASE middleware owner to middleware_user;
GRANT ALL PRIVILEGES ON DATABASE middleware TO middleware_user;
GRANT ALL PRIVILEGES ON all tables in schema public TO middleware_user;