#!/usr/bin/env bash
# Provisions a NeoForge dedicated server for Cyberpunk 2027 on a fresh EC2 instance
# (Amazon Linux 2023 or Ubuntu 22.04+). Run as root, e.g.:
#
#   sudo ./setup-ec2-server.sh
#
# Optional overrides (env vars):
#   REPO_URL       git remote to build the mod from   (default: this repo)
#   REPO_REF       branch/tag/commit to check out      (default: main)
#   NEOFORGE_VERSION                                   (default: 26.2.0.7-beta)
#   MC_SEED        world seed                          (default: 50520260801, the authored city seed)
#   LEVEL_TYPE     world preset                         (default: cyberdeck:cyberpunk_city)
#   JVM_XMX/JVM_XMS   heap sizing, tune to instance RAM (defaults: 10G / 4G, sized for a 32GB box)
#   TS_AUTHKEY     Tailscale auth key for unattended `tailscale up` (optional; omit to run it by hand)
#
# What this does:
#   1. Installs Amazon Corretto 25 (the mod targets Java 25 specifically, see build.gradle)
#   2. Creates an unprivileged `minecraft` service user under /opt/cyberdeck-server
#   3. Clones the repo and builds the mod jar from source (./gradlew build)
#   4. Runs the NeoForge server installer
#   5. Drops in the built cyberdeck jar plus the vendored vehicle_mod jar
#   6. Pre-seeds eula.txt and server.properties with the correct seed/world-type so the
#      FIRST boot generates the actual megacity instead of a throwaway default world
#   7. Installs Tailscale so the server never needs a public-facing port/security-group rule
#   8. Registers a systemd unit with Restart=on-failure so the server survives crashes/reboots
#
# This script does not open any inbound port in your AWS security group, and it doesn't need
# to: connect over the Tailscale IP printed at the end instead of the instance's public IP.

set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/rakk505/cyberpunk-modernity.git}"
REPO_REF="${REPO_REF:-main}"
NEOFORGE_VERSION="${NEOFORGE_VERSION:-26.2.0.7-beta}"
MC_SEED="${MC_SEED:-50520260801}"
LEVEL_TYPE="${LEVEL_TYPE:-cyberdeck:cyberpunk_city}"
JVM_XMX="${JVM_XMX:-10G}"
JVM_XMS="${JVM_XMS:-4G}"
TS_AUTHKEY="${TS_AUTHKEY:-}"

SERVICE_USER=minecraft
SRC_DIR=/opt/cyberdeck-src
SERVER_DIR=/opt/cyberdeck-server
CORRETTO_DIR=/opt/corretto-25

if [[ $EUID -ne 0 ]]; then
    echo "Run this as root (sudo ./setup-ec2-server.sh)" >&2
    exit 1
fi

log() { echo "==> $*"; }

# --- 1. base packages -------------------------------------------------------
if command -v dnf >/dev/null 2>&1; then
    PKG_INSTALL="dnf install -y"
    $PKG_INSTALL git tar gzip wget
elif command -v apt-get >/dev/null 2>&1; then
    PKG_INSTALL="apt-get install -y"
    apt-get update -y
    $PKG_INSTALL git tar gzip wget
else
    echo "Unsupported distro: need dnf (Amazon Linux) or apt-get (Ubuntu/Debian)" >&2
    exit 1
fi

# --- 2. Amazon Corretto 25 (Java 25 is a hard requirement, see build.gradle) --
if [[ ! -x "$CORRETTO_DIR/bin/java" ]]; then
    log "Installing Amazon Corretto 25"
    ARCH="$(uname -m)"
    case "$ARCH" in
        x86_64) CORRETTO_ARCH=x64 ;;
        aarch64) CORRETTO_ARCH=aarch64 ;;
        *) echo "Unsupported architecture: $ARCH" >&2; exit 1 ;;
    esac
    curl -fsSL -o /tmp/corretto25.tar.gz \
        "https://corretto.aws/downloads/latest/amazon-corretto-25-${CORRETTO_ARCH}-linux-jdk.tar.gz"
    mkdir -p "$CORRETTO_DIR"
    tar -xzf /tmp/corretto25.tar.gz -C "$CORRETTO_DIR" --strip-components=1
    rm -f /tmp/corretto25.tar.gz
fi
JAVA_BIN="$CORRETTO_DIR/bin/java"
ln -sf "$JAVA_BIN" /usr/local/bin/java

# --- 3. service user ---------------------------------------------------------
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    log "Creating service user '$SERVICE_USER'"
    useradd --system --create-home --home-dir /home/"$SERVICE_USER" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
mkdir -p "$SRC_DIR" "$SERVER_DIR"
chown -R "$SERVICE_USER":"$SERVICE_USER" "$SRC_DIR" "$SERVER_DIR"

# --- 4. build the mod from source --------------------------------------------
log "Cloning $REPO_URL@$REPO_REF"
if [[ -d "$SRC_DIR/.git" ]]; then
    sudo -u "$SERVICE_USER" git -C "$SRC_DIR" fetch origin "$REPO_REF"
    sudo -u "$SERVICE_USER" git -C "$SRC_DIR" checkout "$REPO_REF"
    sudo -u "$SERVICE_USER" git -C "$SRC_DIR" reset --hard "origin/$REPO_REF"
else
    sudo -u "$SERVICE_USER" git clone --branch "$REPO_REF" "$REPO_URL" "$SRC_DIR"
fi

log "Building the mod (./gradlew build) -- first build downloads/decompiles Minecraft, expect several minutes"
sudo -u "$SERVICE_USER" env JAVA_HOME="$CORRETTO_DIR" PATH="$CORRETTO_DIR/bin:$PATH" \
    "$SRC_DIR/gradlew" -p "$SRC_DIR" build

BUILT_JAR="$(find "$SRC_DIR/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' | head -n1)"
if [[ -z "$BUILT_JAR" ]]; then
    echo "Build did not produce a jar in $SRC_DIR/build/libs" >&2
    exit 1
fi

# --- 5. install the NeoForge server ------------------------------------------
if [[ ! -f "$SERVER_DIR/run.sh" ]]; then
    log "Downloading NeoForge $NEOFORGE_VERSION installer"
    sudo -u "$SERVICE_USER" curl -fsSL -o "$SERVER_DIR/neoforge-installer.jar" \
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/${NEOFORGE_VERSION}/neoforge-${NEOFORGE_VERSION}-installer.jar"
    log "Running the server installer"
    sudo -u "$SERVICE_USER" env JAVA_HOME="$CORRETTO_DIR" PATH="$CORRETTO_DIR/bin:$PATH" \
        bash -c "cd '$SERVER_DIR' && java -jar neoforge-installer.jar --install-server"
fi

# --- 6. mods ------------------------------------------------------------------
log "Installing mods"
mkdir -p "$SERVER_DIR/mods"
cp "$BUILT_JAR" "$SERVER_DIR/mods/"
cp "$SRC_DIR/libs/vehicle_mod-1.0.0.jar" "$SERVER_DIR/mods/"
chown -R "$SERVICE_USER":"$SERVICE_USER" "$SERVER_DIR/mods"

# --- 7. pre-seed eula + server.properties so the first boot is the real city -
log "Accepting EULA and configuring the world (seed=$MC_SEED, level-type=$LEVEL_TYPE)"
cat > "$SERVER_DIR/eula.txt" <<EOF
# Generated by setup-ec2-server.sh. By leaving this as true you are accepting
# the Minecraft EULA: https://aka.ms/MinecraftEULA
eula=true
EOF

cat > "$SERVER_DIR/server.properties" <<EOF
level-name=world
level-seed=$MC_SEED
level-type=$LEVEL_TYPE
motd=Cyberpunk 2027 - Project Moon Megacity
max-players=8
difficulty=easy
online-mode=true
white-list=false
EOF

cat > "$SERVER_DIR/user_jvm_args.txt" <<EOF
# Generated by setup-ec2-server.sh. Tune these to the instance's actual RAM --
# leave a few GB headroom for the OS and file cache above -Xmx.
-Xmx${JVM_XMX}
-Xms${JVM_XMS}
EOF

chown "$SERVICE_USER":"$SERVICE_USER" "$SERVER_DIR"/eula.txt "$SERVER_DIR"/server.properties "$SERVER_DIR"/user_jvm_args.txt
chmod +x "$SERVER_DIR/run.sh"

# --- 8. Tailscale, so we never need to open 25565 publicly --------------------
if ! command -v tailscale >/dev/null 2>&1; then
    log "Installing Tailscale"
    curl -fsSL https://tailscale.com/install.sh | sh
fi
if [[ -n "$TS_AUTHKEY" ]]; then
    log "Bringing up Tailscale with the provided auth key"
    tailscale up --authkey="$TS_AUTHKEY" --ssh
else
    log "Tailscale installed but not connected -- run 'sudo tailscale up' and follow the login link"
fi

# --- 9. systemd service, so it survives crashes and reboots -------------------
log "Installing systemd service"
cat > /etc/systemd/system/cyberdeck.service <<EOF
[Unit]
Description=Cyberpunk 2027 NeoForge Server
After=network.target

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$SERVER_DIR
Environment=JAVA_HOME=$CORRETTO_DIR
Environment=PATH=$CORRETTO_DIR/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=$SERVER_DIR/run.sh nogui
Restart=on-failure
RestartSec=10
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now cyberdeck.service

log "Done. Server is starting under systemd (Restart=on-failure, survives reboots)."
log "  Status:  systemctl status cyberdeck"
log "  Logs:    journalctl -u cyberdeck -f"
if command -v tailscale >/dev/null 2>&1 && tailscale ip -4 >/dev/null 2>&1; then
    log "  Connect: $(tailscale ip -4) : 25565 (over Tailscale -- no public port needed)"
else
    log "  Connect: once Tailscale is up, run 'tailscale ip -4' for the address to give friends"
fi
