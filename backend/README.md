# Backend

## 数据库配置

后端默认连接本机数据库：

```text
jdbc:postgresql://localhost:5432/kskbl?stringtype=unspecified
```

如果你的表建在其他数据库里，启动前设置：

```powershell
$env:DB_URL="jdbc:postgresql://你的host:5432/你的数据库名?stringtype=unspecified"
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="你的密码"
```

如果看到 `database "kskbl" does not exist`，通常说明启动时连接到了另一台 PostgreSQL 或另一个端口；按你当前截图，应该连接本机 `localhost:5432/kskbl`。

## 启动

```powershell
mvn spring-boot:run
```

后端地址：

```text
http://localhost:8081/api
```
