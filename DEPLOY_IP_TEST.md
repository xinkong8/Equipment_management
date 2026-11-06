# 国内云服务器：公网 IP 测试部署

此方案用于以 `http://服务器公网IP` 访问系统。公网仅开放网站端口 `80`；数据库和后端 API 均不对公网开放。

## 1. 服务器与安全组

- Ubuntu 22.04/24.04，建议至少 2 核 4 GB 内存、40 GB 磁盘。
- 安全组入站仅放行 `22/TCP`（SSH）和 `80/TCP`（网页）。
- 不要放行 `5432`、`8080`、`5173`。

## 2. 安装 Docker

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
exit
```

重新 SSH 登录后确认：

```bash
docker --version
docker compose version
```

## 3. 上传项目

上传项目到 `/opt/equipment-management`。可用 WinSCP、VS Code Remote SSH 或 Git。不要上传本地的 `node_modules`、`target`、`backups`。

```bash
sudo mkdir -p /opt/equipment-management
sudo chown -R $USER:$USER /opt/equipment-management
cd /opt/equipment-management
```

## 4. 设置生产密钥

```bash
cp .env.production.example .env
nano .env
```

将 `POSTGRES_PASSWORD` 与 `APP_JWT_SECRET` 改为随机强密码。生成示例：

```bash
openssl rand -base64 36
openssl rand -base64 48
```

## 5. 构建并启动

```bash
docker compose -f docker-compose.production.yml up -d --build
docker compose -f docker-compose.production.yml ps
```

浏览器访问：

```text
http://你的服务器公网IP
```

首个注册账户会成为管理者。

## 6. 日常运维

```bash
# 查看日志
docker compose -f docker-compose.production.yml logs -f

# 更新代码后重新部署
docker compose -f docker-compose.production.yml up -d --build

# 停止服务（不会删除数据库）
docker compose -f docker-compose.production.yml down
```

## 7. 数据备份

```bash
mkdir -p backups
docker compose -f docker-compose.production.yml exec -T postgres \
  pg_dump -U equipment_user equipment_management > backups/equipment-$(date +%F).sql
tar -czf backups/uploads-$(date +%F).tar.gz storage/uploads
```

定期将 `backups/` 下载到本地或对象存储。公网 IP 测试结束后，应改用域名、完成备案（大陆服务器）并配置 HTTPS。
