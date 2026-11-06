# 装备管理系统

本项目使用 Docker Compose 提供本地开发环境：PostgreSQL、Spring Boot 后端和 Vue 前端均在容器中运行。

## 启动

1. 确认 Docker Desktop 已启动。
2. 如需自定义本地密码或端口，编辑根目录的 `.env`；可参考 `.env.example`。
3. 在项目根目录运行：

```powershell
docker compose up --build
```

后台启动：

```powershell
docker compose up --build -d
```

服务地址：

- 前端：<http://localhost:5173>
- 后端健康检查：<http://localhost:8080/api/health>
- PostgreSQL：`localhost:5432`

## 常用命令

```powershell
# 查看服务状态与日志
docker compose ps
docker compose logs -f

# 停止服务（保留数据库数据）
docker compose down

# 停止并删除数据库数据卷，谨慎使用
docker compose down -v
```

附件文件保存在 `storage/uploads/`；PostgreSQL 数据保存在 Docker 管理的 `postgres_data` 卷中。

## 当前功能

- 用户注册、登录与 JWT 认证；第一个注册用户自动成为管理者。
- 管理者可维护装备类别和类别动态字段。
- 登录用户可查看装备；创建者和管理者可编辑装备。
- 装备创建时自动生成 `EQ-000001` 格式的永久编号，并记录状态事件。

首次启动后的注册入口在前端页面。开发环境中已创建用于验证的账号：用户名 `admin`，密码 `admin123`；正式使用前请自行更改或删除该账号。
