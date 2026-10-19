# Deployment (beginner-friendly, from zero)

<p align="center"><a href="deployment.md">简体中文</a> | <b>English</b></p>

This guide assumes you've **never used Docker or typed a command in a terminal before** and just want to get this ledger service running. It's a bit wordy on purpose - every step spells out exactly where to click and what to type.

If you've used Docker before, just jump to the [quick reference](#quick-reference-for-people-who-already-know-docker) at the end.

## What is this, and where does it go

The app has two parts:

- **The phone app**: what you open day to day to look at and record spending.
- **The backend service**: where your data actually lives. It's a program that needs to stay running **24/7**, on a device that doesn't get turned off.

This document is about setting up that backend service. Common choices:

- **A NAS** (Synology, QNAP, 极空间/ZSpace, etc.) - the most recommended option, since it's already on all the time and uses little power.
- A Raspberry Pi, Orange Pi, or similar small single-board computer
- An old PC at home that's always on
- A cloud VPS (costs money every month)

Whichever you pick, that device needs to be able to run **Docker** (software that packages up a ready-to-run environment; almost every NAS's app store has it).

## Step 1: Make sure your device can run Docker

### If it's a NAS (Synology / QNAP / 极空间 and similar)

Go to the NAS's own "App Center" or "Software Center" and search for **Docker** or **Container Manager**, and install it like any other app - you don't need to understand how it works under the hood.

### If it's a Raspberry Pi / Orange Pi / a regular PC running Linux

You'll need to use a terminal (connect over SSH, or plug in a monitor and keyboard directly) and run Docker's official one-line installer:

```bash
curl -fsSL https://get.docker.com | sh
```

If you have no idea what a terminal is, it's worth asking a friend who knows Linux for help with this step, or going with the NAS route above instead.

## Step 2: Download this project's code

**Option A (recommended for beginners): download the ZIP directly**

1. Open [https://github.com/EdgeN8v/Ledger](https://github.com/EdgeN8v/Ledger)
2. Click the green **Code** button → **Download ZIP**
3. Copy the ZIP onto your NAS (using the NAS's own File Station / File Manager app, or a tool like [FileZilla](https://filezilla-project.org/) to drag it over), and unzip it into a folder whose path you'll remember, e.g. `/docker/ledger/`

**Option B (if you're comfortable with the command line)**

```bash
git clone https://github.com/EdgeN8v/Ledger.git
cd Ledger
```

## Step 3: Open a terminal and go into that folder

This step is **unavoidable** - the remaining steps all involve typing commands in a terminal.

- **Synology**: Control Panel → Terminal & SNMP → check "Enable SSH service", then connect with [PuTTY](https://www.putty.org/) (Windows) or the Terminal app (Mac), using the same username/password you use to log into the NAS.
- **极空间 (ZSpace)**: similar idea - enable SSH under "Settings → General" or "Security Management", then connect the same way with PuTTY/Terminal.
- **Raspberry Pi and other Linux boxes**: connect over SSH the same way, or just open a terminal directly if you have a monitor/keyboard attached.

Once connected, use the `cd` command to go into the folder you unzipped/cloned into, e.g.:

```bash
cd /volume1/docker/Ledger
```

(The exact path depends on where you unzipped it. If you're not sure, use `ls` to see what's in the current folder and `cd` your way in step by step.)

## Step 4: Prepare the config file (two passwords)

This step tells the program "use this as the database password" and "use this secret to decide who's allowed to log in".

First, copy the template config file:

```bash
cp .env.example .env
```

Then open the newly created `.env` file to edit it (in the terminal you can use `nano .env`; if you don't know `nano`, you can also just open it with the NAS's own text editor in its file manager).

Change these two lines to your own values:

```ini
POSTGRES_PASSWORD=pick any password of your own
JWT_SECRET=change this to a long random string
```

- `POSTGRES_PASSWORD`: the database's password. Make up anything - it doesn't matter if you can't remember it, since you'll never need to type it manually.
- `JWT_SECRET`: this decides "whose login is valid", so it needs to be long and genuinely random - **don't use your birthday or phone number**. If you want the easy way out, run this in the terminal and paste the result in:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(48))"
```

(With `nano`, press `Ctrl+O` to save, Enter to confirm, then `Ctrl+X` to exit.)

## Step 5: Start it with one command

```bash
docker compose up -d --build
```

The first run takes a few minutes (it needs to download and build things) - the terminal will scroll a bunch of text; just wait for it to finish and give you back a prompt.

Check that it worked:

```bash
curl http://localhost:8000/health
```

Seeing `{"status":"ok"}` means the backend is up and running.

> **Getting a "JWT_SECRET must be set" error?** It means the `.env` edit from step 4 either didn't go through or wasn't saved - go back and double-check it.
>
> **Can't find a `docker compose` command in the Synology/ZSpace interface?** Use their own "Container Manager / Container Station" app's "Project" feature instead, and import the `docker-compose.yml` file from this folder, then deploy - same result.
>
> **NAS has an ARM chip (common on many 极空间 and QNAP models)?** No special action needed - the command above builds the right version for that chip directly on the NAS itself.

## Step 6: Create your own account

Registration is **closed by default**, so strangers can't sign up on your server. You need to open it briefly the first time:

1. Go back to the `.env` file and add this line at the end:

   ```ini
   REGISTRATION_OPEN=true
   ```

2. Apply the change:

   ```bash
   docker compose up -d
   ```

3. Open the app on your phone, enter your server address (the next step explains how to fill it in), tap "Create ledger", and register your own account.
4. Other people in your household don't need registration opened again - they can use "Join ledger" + the invite code (visible on the app's "Me" page) to join the same ledger.
5. Once everyone's registered, remove that `REGISTRATION_OPEN=true` line from `.env` (or comment it out with a leading `#`) and run `docker compose up -d` once more to close registration again.

## Step 7: Point the phone at your server

Open the app, and on the login page there's a "Server address" field. Fill it in based on which of these you're using - **start with the first one, and only bother with the others if you actually need them**:

| Method | What to enter | Who it's for |
|---|---|---|
| Home Wi-Fi only | `http://your-NAS-LAN-IP:8000/` (find the IP in the NAS's system info, usually something like `192.168.x.x`) | Simplest and safest. Won't sync while out, but the phone stores everything locally and uploads it once you're back on the home network - nothing gets lost. |
| Also want it while out, via VPN | Install WireGuard or Tailscale on the NAS and on your phone, then enter the VPN's internal address as the server address | For people who want to record on the go without exposing the port to the public internet. |
| Also want it while out, direct public access | Use Caddy / Nginx with HTTPS and a domain pointed at it. **Never** expose port 8000 directly to the public internet | Not recommended for beginners - easy to leave a security hole. |

## Ongoing maintenance

### Backups

`docker-compose.yml` already includes a small backup container that dumps the database to `./backups/` every night automatically, keeping the last 14 days. **But what if the NAS itself dies?** It's worth occasionally copying the whole `backups/` folder to another drive or cloud storage as a second line of defense.

To restore from a backup (e.g. on a new machine):

```bash
zcat backups/ledger-DATE.sql.gz | docker compose exec -T db psql -U ledger -d ledger
```

(Restore into a brand-new, empty database.)

### Upgrading to a new version

When this project gets updated and you want the latest code:

```bash
git pull
docker compose up -d --build
```

(If you installed via "Download ZIP", just download the latest ZIP again, copy your `.env` file into it, and run `docker compose up -d --build`.)

If a database schema change is needed (e.g. a new column was added), the release notes will give you a one-line statement like `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...` - run it once through `docker compose exec -T db psql -U ledger -d ledger` and you're done. These statements only ever add, never drop, so nothing gets lost.

### Phone permissions

The phone also needs a few permissions granted (notification access, SMS reading, disabling Xiaomi's battery/autostart restrictions) - that part has nothing to do with the backend and is covered in the [README's "Permissions on the phone" section](../README_EN.md#permissions-on-the-phone).

---

## Quick reference (for people who already know Docker)

```bash
git clone https://github.com/EdgeN8v/Ledger.git
cd Ledger
cp .env.example .env
# edit .env: POSTGRES_PASSWORD, JWT_SECRET
docker compose up -d --build
curl http://localhost:8000/health        # {"status":"ok"}
# temporarily set REGISTRATION_OPEN=true, register your accounts, then turn it back off
```

App server address: home LAN direct is simplest; use a VPN (WireGuard/Tailscale) to also use it while out; don't expose port 8000 directly to the public internet. See the `backup` container above for backups; upgrade with `git pull && docker compose up -d --build`.
