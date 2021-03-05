-- --------------------------------------------------
-- 创建用户赋予权限
-- --------------------------------------------------
-- \c middleware;
CREATE USER middleware_user WITH PASSWORD '860fbbdd75de0b5d517323d6f1832e10';
ALTER DATABASE middleware owner to middleware_user;
GRANT ALL PRIVILEGES ON DATABASE middleware TO middleware_user;

GRANT ALL PRIVILEGES ON all tables in schema public TO middleware_user;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO middleware_user;

alter table public.action owner to middleware_user;
alter table public.job owner to middleware_user;
alter table public.data_source owner to middleware_user;

-- DO
-- $$
--     DECLARE
--         r           record;
--         v_schema    text[]  := '{public,schema1}';
--         v_new_owner varchar := 'middleware_user';
--     BEGIN
--         FOR r IN
--             SELECT 'ALTER TABLE "' || table_schema || '"."' || table_name || '" OWNER TO ' || v_new_owner ||
--                    ';' AS a
--             FROM information_schema.tables
--             UNION ALL
--             SELECT 'ALTER TABLE "' || sequence_schema || '"."' || sequence_name || '" OWNER TO ' || v_new_owner ||
--                    ';' AS a
--             FROM information_schema.sequences
--             WHERE sequence_schema = ANY (v_schema)
--             UNION ALL
--             SELECT 'ALTER TABLE "' || table_schema || '"."' || table_name || '" OWNER TO ' || v_new_owner || ';' AS a
--             FROM information_schema.views
--             WHERE table_schema = ANY (v_schema)
--             LOOP
--                 EXECUTE r.a;
--             END LOOP;
--     END
-- $$;