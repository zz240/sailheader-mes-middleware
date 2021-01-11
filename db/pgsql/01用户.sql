

-- 密码是 Sailheader@2018
INSERT INTO ad_user (logon_name,password,gender,user_name,enabled,status,created_by,created_date,is_manager) VALUES (
'admin','$2a$10$/HbvVqHcuVMwRZ2Ik8Pmb.dvMWeBvfi9Wc1cFnmwM5SoC1KohtedC',1,'管理员',true,'ACTIVE',1,now(),TRUE );

INSERT INTO ad_group (en_us_name,zh_cn_name,type,enabled,status,created_by,created_date) VALUES (
  'Everyone','Everyone','Everyone',true,'ACTIVE',1,now());

