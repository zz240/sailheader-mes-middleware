DROP TABLE IF EXISTS customer_db.dbg_product;
CREATE TABLE customer_db.dbg_product (
  id int primary key auto_increment,
  order_no varchar(32),
  status varchar(16),
  `items[0].materialCode` varchar(32),
  `items[1].materialCode` varchar(32),
  `items[0].qty` int,
  `items[0].unit` varchar(16)
);
INSERT INTO customer_db.dbg_product (order_no, status, `items[0].materialCode`, `items[1].materialCode`, `items[0].qty`, `items[0].unit`)
VALUES ('DBG-9001', 'ACTIVE', 'MAT-718H', 'MAT-SKD61', 1, 'PCS');
SELECT * FROM customer_db.dbg_product;
