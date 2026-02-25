#!/bin/bash

# fytFM Release Script
# Baut die APK, erstellt Tag und pusht zu GitHub

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INI_FILE="$SCRIPT_DIR/release.ini"

# Config laden
load_config() {
    if [ ! -f "$INI_FILE" ]; then
        echo "Fehler: release.ini nicht gefunden!"
        echo "Erwartet unter: $INI_FILE"
        exit 1
    fi

    APP_PATH=$(grep -E "^APP_PATH=" "$INI_FILE" | cut -d'=' -f2-)
    GITHUB_TOKEN=$(grep -E "^GITHUB_TOKEN=" "$INI_FILE" | cut -d'=' -f2 | tr -d ' ')
    GITHUB_USER=$(grep -E "^GITHUB_USER=" "$INI_FILE" | cut -d'=' -f2 | tr -d ' ')
    SIGN_KEY=$(grep -E "^SIGN_KEY=" "$INI_FILE" | cut -d'=' -f2-)
    SIGN_KEY_ALIAS=$(grep -E "^SIGN_KEY_ALIAS=" "$INI_FILE" | cut -d'=' -f2 | tr -d ' ')
    SIGN_KEY_PASS=$(grep -E "^SIGN_KEY_PASS=" "$INI_FILE" | cut -d'=' -f2 | tr -d ' ')

    # Signkey-Pfad (relativ zum Script oder absolut)
    if [[ "$SIGN_KEY" != /* ]]; then
        SIGN_KEY_PATH="$SCRIPT_DIR/$SIGN_KEY"
    else
        SIGN_KEY_PATH="$SIGN_KEY"
    fi
    SIGN_CERT="${SIGN_KEY_PATH%.pk8}.x509.pem"
}

# Menü Option 1: GitHub Secrets anzeigen
show_github_secrets() {
    echo ""
    echo "========================================="
    echo "    GitHub Secrets Generator"
    echo "========================================="
    echo ""
    echo "Keystore: $SIGN_KEY_PATH"
    echo ""

    if [ ! -f "$SIGN_KEY_PATH" ]; then
        echo "Fehler: $SIGN_KEY_PATH nicht gefunden!"
        return 1
    fi

    # Prüfen ob PKCS12 Format (enthält Key + Zertifikat)
    if openssl pkcs12 -info -in "$SIGN_KEY_PATH" -passin "pass:$SIGN_KEY_PASS" -noout 2>/dev/null; then
        echo "Format: PKCS#12 (Key + Zertifikat in einer Datei)"
        echo ""

        # Key und Zertifikat extrahieren für GitHub
        TEMP_KEY="/tmp/gh_signkey.pk8"
        TEMP_CERT="/tmp/gh_signkey.x509.pem"

        # Private Key extrahieren (unverschlüsselt für GitHub)
        openssl pkcs12 -in "$SIGN_KEY_PATH" -passin "pass:$SIGN_KEY_PASS" \
            -nocerts -nodes -out /tmp/gh_key.pem 2>/dev/null
        openssl pkcs8 -topk8 -inform PEM -outform DER -in /tmp/gh_key.pem \
            -out "$TEMP_KEY" -nocrypt 2>/dev/null
        rm -f /tmp/gh_key.pem

        # Zertifikat extrahieren
        openssl pkcs12 -in "$SIGN_KEY_PATH" -passin "pass:$SIGN_KEY_PASS" \
            -nokeys -out "$TEMP_CERT" 2>/dev/null

        echo "Key und Zertifikat extrahiert."
        echo ""
        echo "Kopiere diese Werte in GitHub:"
        echo "  Repository -> Settings -> Secrets -> Actions"
        echo ""
        echo "========================================="
        echo "Secret Name: SIGN_KEY"
        echo "========================================="
        base64 -w 0 "$TEMP_KEY"
        echo ""
        echo ""
        echo "========================================="
        echo "Secret Name: SIGN_CERT"
        echo "========================================="
        base64 -w 0 "$TEMP_CERT"
        echo ""
        echo ""
        echo "========================================="

        # Aufräumen
        rm -f "$TEMP_KEY" "$TEMP_CERT"
    else
        # Fallback: Normale pk8 + x509.pem
        echo "Format: PKCS#8 + separates Zertifikat"

        if [ ! -f "$SIGN_CERT" ]; then
            echo "Fehler: $SIGN_CERT nicht gefunden!"
            return 1
        fi

        echo ""
        echo "Kopiere diese Werte in GitHub:"
        echo "  Repository -> Settings -> Secrets -> Actions"
        echo ""
        echo "========================================="
        echo "Secret Name: SIGN_KEY"
        echo "========================================="
        base64 -w 0 "$SIGN_KEY_PATH"
        echo ""
        echo ""
        echo "========================================="
        echo "Secret Name: SIGN_CERT"
        echo "========================================="
        base64 -w 0 "$SIGN_CERT"
        echo ""
        echo ""
        echo "========================================="
    fi

    echo ""
    read -p "Weiter mit Enter..."
}

# GitHub Secrets hochladen
upload_github_secrets() {
    echo ""
    echo "Lade Signing Keys zu GitHub hoch..."
    echo ""

    # Prüfen ob Credentials vorhanden
    if [ -z "$GITHUB_TOKEN" ] || [ -z "$GITHUB_USER" ]; then
        echo "Fehler: GITHUB_TOKEN oder GITHUB_USER nicht in release.ini!"
        return 1
    fi

    # Repo-Name ermitteln
    cd "$APP_PATH"
    REPO_NAME=$(git remote get-url origin | sed -E 's#.*/##' | sed 's/\.git$//')
    echo "Repository: $GITHUB_USER/$REPO_NAME"

    # GitHub CLI mit Token aus ini verwenden
    export GH_TOKEN="$GITHUB_TOKEN"

    if ! command -v gh &> /dev/null; then
        echo ""
        echo "GitHub CLI (gh) nicht installiert."
        echo "Installieren mit: sudo apt install gh"
        echo ""
        echo "Alternativ manuell die Secrets kopieren (Option 3)"
        return 1
    fi

    # JKS Format - Keystore direkt als Base64 hochladen
    if [[ "$SIGN_KEY_PATH" == *.jks ]]; then
        echo "JKS Keystore erkannt..."

        # Ganzen Keystore als Base64 kodieren
        KEYSTORE_B64=$(base64 -w 0 "$SIGN_KEY_PATH")

        echo "Lade Secrets zu GitHub hoch..."
        echo "$KEYSTORE_B64" | gh secret set KEYSTORE_BASE64 --repo "$GITHUB_USER/$REPO_NAME"
        echo "$SIGN_KEY_PASS" | gh secret set KEYSTORE_PASSWORD --repo "$GITHUB_USER/$REPO_NAME"
        echo "${SIGN_KEY_ALIAS:-platform}" | gh secret set KEY_ALIAS --repo "$GITHUB_USER/$REPO_NAME"
        echo "$SIGN_KEY_PASS" | gh secret set KEY_PASSWORD --repo "$GITHUB_USER/$REPO_NAME"

        echo ""
        echo "GitHub Secrets erfolgreich hochgeladen!"
        echo ""
        echo "Secrets erstellt:"
        echo "  - KEYSTORE_BASE64"
        echo "  - KEYSTORE_PASSWORD"
        echo "  - KEY_ALIAS"
        echo "  - KEY_PASSWORD"

    # PKCS#12 Format - Key und Cert extrahieren
    elif openssl pkcs12 -info -in "$SIGN_KEY_PATH" -passin "pass:$SIGN_KEY_PASS" -noout 2>/dev/null; then
        echo "PKCS#12 Keystore erkannt..."

        TEMP_KEY="/tmp/gh_signkey.pk8"
        TEMP_CERT="/tmp/gh_signkey.x509.pem"

        # Private Key extrahieren
        openssl pkcs12 -in "$SIGN_KEY_PATH" -passin "pass:$SIGN_KEY_PASS" \
            -nocerts -nodes -out /tmp/gh_key.pem 2>/dev/null
        openssl pkcs8 -topk8 -inform PEM -outform DER -in /tmp/gh_key.pem \
            -out "$TEMP_KEY" -nocrypt 2>/dev/null
        rm -f /tmp/gh_key.pem

        # Zertifikat extrahieren
        openssl pkcs12 -in "$SIGN_KEY_PATH" -passin "pass:$SIGN_KEY_PASS" \
            -nokeys -out "$TEMP_CERT" 2>/dev/null

        # Base64 kodieren
        SIGN_KEY_B64=$(base64 -w 0 "$TEMP_KEY")
        SIGN_CERT_B64=$(base64 -w 0 "$TEMP_CERT")

        rm -f "$TEMP_KEY" "$TEMP_CERT"

        echo "Lade Secrets zu GitHub hoch..."
        echo "$SIGN_KEY_B64" | gh secret set SIGN_KEY --repo "$GITHUB_USER/$REPO_NAME"
        echo "$SIGN_CERT_B64" | gh secret set SIGN_CERT --repo "$GITHUB_USER/$REPO_NAME"

        echo ""
        echo "GitHub Secrets erfolgreich hochgeladen!"
    else
        echo "Fehler: Keystore-Format nicht erkannt"
        echo "Unterstützt: .jks (Java KeyStore), .p12/.pfx (PKCS#12)"
        return 1
    fi
}

# Menü Option 2: Release erstellen
create_release() {
    # App-Pfad prüfen
    if [ -z "$APP_PATH" ] || [ ! -d "$APP_PATH" ]; then
        echo "Fehler: APP_PATH ungültig oder nicht gefunden!"
        echo "Aktueller Wert: $APP_PATH"
        exit 1
    fi

    echo ""
    echo "App-Pfad: $APP_PATH"

    # In App-Verzeichnis wechseln
    cd "$APP_PATH"

    # Prüfen ob wir im richtigen Verzeichnis sind
    if [ ! -f "gradlew" ]; then
        echo "Fehler: gradlew nicht gefunden in $APP_PATH"
        exit 1
    fi

    # Token-Status anzeigen
    if [ -z "$GITHUB_TOKEN" ]; then
        echo "Fehler: GITHUB_TOKEN in release.ini ist leer!"
        echo "Token erstellen: https://github.com/settings/tokens"
        exit 1
    fi
    echo "GitHub Token geladen für User: $GITHUB_USER"

    # Aktuelle Version aus build.gradle.kts lesen
    GRADLE_FILE="app/build.gradle.kts"
    CURRENT_VERSION_NAME=$(grep -E "versionName\s*=" "$GRADLE_FILE" | sed -E 's/.*"(.*)".*/\1/')
    CURRENT_VERSION_CODE=$(grep -E "versionCode\s*=" "$GRADLE_FILE" | sed -E 's/.*=\s*([0-9]+).*/\1/')

    echo ""
    echo "Aktuelle Version: v$CURRENT_VERSION_NAME (Code: $CURRENT_VERSION_CODE)"

    # Letzte Tags anzeigen
    echo ""
    echo "Letzte Tags:"
    git tag --sort=-v:refname | head -5 || echo "  (keine Tags vorhanden)"
    echo ""

    # Version abfragen
    read -p "Neue Version (z.B. v1.0.0): " VERSION

    # Validieren
    if [[ ! $VERSION =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "Fehler: Version muss Format v1.2.3 haben"
        exit 1
    fi

    # Version ohne 'v' Prefix
    VERSION_NAME="${VERSION#v}"

    # VersionCode berechnen: major*10000 + minor*100 + patch
    MAJOR=$(echo "$VERSION_NAME" | cut -d'.' -f1)
    MINOR=$(echo "$VERSION_NAME" | cut -d'.' -f2)
    PATCH=$(echo "$VERSION_NAME" | cut -d'.' -f3)
    VERSION_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

    echo ""
    echo "Neue Version: $VERSION_NAME (Code: $VERSION_CODE)"

    # build.gradle.kts aktualisieren
    echo ""
    echo "Aktualisiere build.gradle.kts..."

    sed -i "s/versionCode = [0-9]*/versionCode = $VERSION_CODE/" "$GRADLE_FILE"
    sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VERSION_NAME\"/" "$GRADLE_FILE"

    # Änderungen anzeigen
    echo "Geändert:"
    grep -E "(versionCode|versionName)" "$GRADLE_FILE" | head -2

    # Prüfen ob Tag schon existiert - wenn ja, löschen
    if git rev-parse "$VERSION" >/dev/null 2>&1; then
        echo ""
        echo "Tag $VERSION existiert bereits - wird gelöscht..."
        git tag -d "$VERSION" 2>/dev/null || true
        REPO_NAME_TMP=$(git remote get-url origin | sed -E 's#.*/##' | sed 's/\.git$//')
        if [ -n "$GITHUB_TOKEN" ] && [ -n "$GITHUB_USER" ]; then
            git push "https://${GITHUB_USER}:${GITHUB_TOKEN}@github.com/${GITHUB_USER}/${REPO_NAME_TMP}" --delete "$VERSION" 2>/dev/null || true
        else
            git push origin --delete "$VERSION" 2>/dev/null || true
        fi
    fi

    # Zusammenfassung
    echo ""
    echo "========================================="
    echo "Release Zusammenfassung:"
    echo "  Version:     $VERSION"
    echo "  VersionName: $VERSION_NAME"
    echo "  VersionCode: $VERSION_CODE"
    echo ""
    echo "  GitHub Actions wird bauen & signieren!"
    echo "========================================="
    echo ""

    read -p "Release erstellen und pushen? (j/n): " confirm
    if [ "$confirm" != "j" ]; then
        echo "Abgebrochen. Änderungen rückgängig machen mit:"
        echo "  git checkout $GRADLE_FILE"
        exit 1
    fi

    # Änderungen committen
    echo ""
    echo "Committe Versionsänderung..."
    git add "$GRADLE_FILE"

    # Workflow auch hinzufügen falls vorhanden
    if [ -f ".github/workflows/release.yml" ]; then
        git add .github/workflows/release.yml
    fi

    git commit -m "Release $VERSION

- versionName: $VERSION_NAME
- versionCode: $VERSION_CODE"

    # Tag erstellen
    echo ""
    echo "Erstelle Tag $VERSION..."
    git tag "$VERSION"

    # Pushen mit Token-Auth
    echo "Pushe zu GitHub..."

    REPO_NAME=$(git remote get-url origin | sed -E 's#.*/##' | sed 's/\.git$//')

    if [ -n "$GITHUB_TOKEN" ] && [ -n "$GITHUB_USER" ]; then
        AUTH_URL="https://${GITHUB_USER}:${GITHUB_TOKEN}@github.com/${GITHUB_USER}/${REPO_NAME}"
        git push "$AUTH_URL" master 2>/dev/null || git push "$AUTH_URL" main 2>/dev/null || true
        git push "$AUTH_URL" "$VERSION"
    else
        git push origin master 2>/dev/null || git push origin main 2>/dev/null || true
        git push origin "$VERSION"
    fi

    echo ""
    echo "========================================="
    echo "Tag $VERSION gepusht!"
    echo ""
    echo "GitHub Actions baut & signiert jetzt..."
    echo "Release erscheint in ~2-3 Minuten unter:"
    echo "https://github.com/${GITHUB_USER}/${REPO_NAME}/releases"
    echo "========================================="
}

# Hauptmenü
show_menu() {
    clear
    echo "========================================="
    echo "        fytFM Release Script"
    echo "========================================="
    echo ""
    echo "  1) GitHub Secrets hochladen"
    echo "     (Signing Keys einmalig zu GitHub)"
    echo ""
    echo "  2) Release erstellen"
    echo "     (Version setzen, Tag pushen)"
    echo "     -> GitHub Actions baut & signiert"
    echo ""
    echo "  3) Beenden"
    echo ""
    echo "========================================="
    read -p "Auswahl [1-3]: " choice
}

# Main
load_config

# Wenn Parameter übergeben, direkt ausführen
if [ "$1" == "--upload" ]; then
    upload_github_secrets
    exit 0
elif [ "$1" == "--secrets" ]; then
    show_github_secrets
    exit 0
elif [ "$1" == "--release" ]; then
    create_release
    exit 0
fi

# Interaktives Menü
while true; do
    show_menu
    case $choice in
        1)
            upload_github_secrets
            read -p "Weiter mit Enter..."
            ;;
        2)
            create_release
            exit 0
            ;;
        3)
            echo "Auf Wiedersehen!"
            exit 0
            ;;
        *)
            echo "Ungültige Auswahl!"
            sleep 1
            ;;
    esac
done
