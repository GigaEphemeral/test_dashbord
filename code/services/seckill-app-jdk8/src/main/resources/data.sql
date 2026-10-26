-- demo profile seed. Rows are byte-identical to the lab's shared MySQL seed
-- (seckill-app-jdk21 Flyway V2__seed_catalog_products.sql + V3 inventory), the
-- only difference being the explicit ids: MySQL takes them from AUTO_INCREMENT
-- starting at 1, so id 1 = 极客机械键盘 / 299.00 and id 20 = 智能插座 / 79.00,
-- each with available = 100. The k6 baseline (PRODUCT_ID=1, SECKILL_PRODUCT_ID=20)
-- and verify.sh/smoke.sh assertions therefore read the same data on both shells.
INSERT INTO catalog_product (id, name, price, status, description) VALUES
(1, '极客机械键盘', 299.00, 'ON_SALE', '87 键热插拔轴体，PBT 键帽'),
(2, '降噪蓝牙耳机', 499.00, 'ON_SALE', '主动降噪，40 小时续航'),
(3, '便携显示器', 1099.00, 'ON_SALE', '15.6 英寸 1080P，USB-C 单线连接'),
(4, '智能手环', 199.00, 'ON_SALE', '心率与睡眠监测，5ATM 防水'),
(5, '无线充电板', 129.00, 'ON_SALE', '15W 快充，支持 Qi 协议'),
(6, '人体工学椅', 1599.00, 'ON_SALE', '四向腰托，网布坐垫'),
(7, '桌面升降台', 899.00, 'ON_SALE', '双层结构，气弹簧升降'),
(8, '极简双肩包', 359.00, 'ON_SALE', '防泼水面料，16 英寸电脑仓'),
(9, '保温杯', 89.00, 'ON_SALE', '316 不锈钢，12 小时保温'),
(10, '护眼台灯', 219.00, 'ON_SALE', '无频闪，色温无级调节'),
(11, '蓝牙音箱', 329.00, 'ON_SALE', '360 度环绕声，IPX7 防水'),
(12, '高速固态硬盘', 649.00, 'ON_SALE', '1TB NVMe，顺序读取 3500MB/s'),
(13, '千兆路由器', 279.00, 'ON_SALE', 'Wi-Fi 6 双频并发，四天线'),
(14, '游戏鼠标', 189.00, 'ON_SALE', '26000 DPI，轻量化设计'),
(15, '电竞鼠标垫', 59.00, 'ON_SALE', '超大尺寸，锁边工艺'),
(16, '双口充电器', 99.00, 'ON_SALE', '65W 氮化镓，双口智能分配'),
(17, '磁吸手机支架', 69.00, 'ON_SALE', '铝合金材质，360 度旋转'),
(18, '桌面收纳盒', 49.00, 'ON_SALE', '可叠放，磨砂质感'),
(19, '降噪耳塞', 39.00, 'ON_SALE', '硅胶材质，可水洗'),
(20, '智能插座', 79.00, 'ON_SALE', '远程控制，电量统计');

-- Identical to Flyway V3: every catalog product starts at exactly 100 units.
INSERT INTO inventory_stock (product_id, available)
SELECT id, 100
FROM catalog_product;
