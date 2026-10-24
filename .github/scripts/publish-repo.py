"""Публикация собранного расширения в персональный репозиторий.

Запуск: `python publish-repo.py <signing-fingerprint> <source-sha>` из чекаута ветки
`repo` (текущий каталог). Артефакты сборки лежат в `~/apk-artifacts`: каталог на модуль
с `keiyoushi-source-info.json`, release-APK и подписанным jar.

Что делает:
  1. загружает APK и jar в GitHub Release с тегом `<модуль>-<версия>`;
  2. пишет index.json / index.pb / index.min.json / index.html / README.md /
     repo.json / release-assets.json в текущий каталог;
  3. удаляет старые релизы, оставляя KEEP_RELEASES последних.

Коммит и push делает workflow — скрипт трогает только рабочее дерево.
"""

import gzip
import hashlib
import html
import json
import sys
from pathlib import Path

from google.protobuf import json_format

import index_pb2
from github_utils import REPO_NAME, run_gh

ARTIFACTS_DIR = Path.home() / "apk-artifacts"
REPO_DIR = Path.cwd()
# <repo>/.github/scripts/publish-repo.py → <repo>
SOURCE_DIR = Path(__file__).resolve().parents[2]

INDEX_NAME = "Hametovbr"
BADGE_LABEL = "HBR"
SOURCE_REPO = "hametovbr/extensions-source"
WEBSITE = f"https://github.com/{REPO_NAME}"
ICON_BASE_URL = f"https://cdn.jsdelivr.net/gh/{SOURCE_REPO}@main"
ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"
RELEASE_BASE_URL = f"https://github.com/{REPO_NAME}/releases/download"
KEEP_RELEASES = 2

if len(sys.argv) != 3:
    sys.exit("usage: publish-repo.py <signing-fingerprint> <source-sha>")

signing_key, source_sha = sys.argv[1], sys.argv[2]


def icon_url(module: str, theme: str | None) -> str:
    module_icon = f"src/{module.replace('.', '/')}/{ICON_FILE}"
    if (SOURCE_DIR / module_icon).is_file():
        return f"{ICON_BASE_URL}/{module_icon}"

    if theme:
        theme_icon = f"lib-multisrc/{theme}/{ICON_FILE}"
        if (SOURCE_DIR / theme_icon).is_file():
            return f"{ICON_BASE_URL}/{theme_icon}"

    return f"{ICON_BASE_URL}/core/src/main/{ICON_FILE}"


def release_tag(info: dict) -> str:
    return f"{info['module'].split('.')[-1]}-{info['versionName']}"


def release_exists(tag: str) -> bool:
    try:
        run_gh("release", "view", tag, "-R", REPO_NAME, "--json", "tagName")
        return True
    except RuntimeError:
        return False


def collect_artifacts() -> list[tuple[dict, Path, Path]]:
    artifacts = []
    for info_file in sorted(ARTIFACTS_DIR.glob("**/keiyoushi-source-info.json")):
        info = json.loads(info_file.read_text(encoding="utf-8"))
        apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
        jar = next((info_file.parent / "outputs/jar/release").glob("*.jar"), None)
        if apk is None or jar is None:
            sys.exit(f"{info['packageName']}: нет APK или jar в {info_file.parent}")
        artifacts.append((info, apk, jar))
    if not artifacts:
        sys.exit(f"в {ARTIFACTS_DIR} не нашлось ни одного keiyoushi-source-info.json")
    return artifacts


def upload_release(info: dict, apk: Path, jar: Path) -> str:
    tag = release_tag(info)
    if not release_exists(tag):
        run_gh(
            "release", "create", tag,
            "-R", REPO_NAME,
            "--title", f"{info['name']} {info['versionName']}",
            "--notes", f"{info['name']} {info['versionName']}, коммит {source_sha[:7]}.",
        )
    run_gh("release", "upload", tag, str(apk), str(jar), "-R", REPO_NAME, "--clobber")
    print(f"Опубликован релиз {tag}: {apk.name}, {jar.name}")
    return tag


def prune_releases(prefixes: set[str]) -> None:
    listing = run_gh(
        "release", "list", "-R", REPO_NAME,
        "--json", "tagName,createdAt", "--limit", "100",
    )
    releases = [r for r in json.loads(listing or "[]") if r["tagName"].split("-")[0] in prefixes]
    releases.sort(key=lambda r: r["createdAt"], reverse=True)
    for stale in releases[KEEP_RELEASES:]:
        tag = stale["tagName"]
        run_gh("release", "delete", tag, "-R", REPO_NAME, "--yes", "--cleanup-tag")
        print(f"Удалён старый релиз {tag}")


def build_index(artifacts: list[tuple[dict, Path, Path]]) -> tuple[index_pb2.Index, dict]:
    extensions = []
    release_assets = {}
    for info, apk, jar in artifacts:
        package_name = info["packageName"]
        tag = release_tag(info)
        release_assets[package_name] = {
            "apk": {
                "name": apk.name,
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            },
            "jar": {
                "name": jar.name,
                "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            },
        }
        extensions.append(
            index_pb2.Extension(
                name=info["name"],
                packageName=package_name,
                resources=index_pb2.Resources(
                    apkUrl=f"{RELEASE_BASE_URL}/{tag}/{apk.name}",
                    iconUrl=icon_url(info["module"], info.get("theme")),
                    jarUrl=f"{RELEASE_BASE_URL}/{tag}/{jar.name}",
                ),
                extensionLib=info["extensionLib"],
                versionCode=info["versionCode"],
                versionName=info["versionName"],
                contentWarning=info["contentWarning"],
                sources=[
                    index_pb2.Source(
                        id=int(source["id"]),
                        name=source["name"],
                        language=source["lang"],
                        homeUrl=source["baseUrl"],
                        mirrorUrls=source.get("mirrorUrls", []),
                    )
                    for source in info["sources"]
                ],
            )
        )

    extensions.sort(key=lambda ext: ext.packageName)
    index = index_pb2.Index(
        name=INDEX_NAME,
        badgeLabel=BADGE_LABEL,
        signingKey=signing_key,
        contact=index_pb2.Contact(website=WEBSITE),
        extensionList=index_pb2.ExtensionList(extensions=extensions),
    )
    return index, release_assets


def build_legacy_index(artifacts: list[tuple[dict, Path, Path]]) -> list[dict]:
    """index.min.json старого формата: его читают Suwayomi/Tachiyomi до перехода на index.pb."""
    legacy = []
    for info, apk, _ in artifacts:
        tag = release_tag(info)
        legacy.append(
            {
                "name": info["name"],
                "pkg": info["packageName"],
                "apk": apk.name,
                "lang": info["sources"][0]["lang"] if info["sources"] else "all",
                "code": info["versionCode"],
                "version": info["versionName"],
                "nsfw": 1 if info.get("contentWarning", 0) == 3 else 0,
                "sources": [
                    {
                        "name": source["name"],
                        "lang": source["lang"],
                        "id": str(source["id"]),
                        "baseUrl": source["baseUrl"],
                    }
                    for source in info["sources"]
                ],
                "apk_url": f"{RELEASE_BASE_URL}/{tag}/{apk.name}",
            }
        )
    return legacy


def write_readme(index: index_pb2.Index) -> None:
    rows = ["| Расширение | Версия | versionCode | Пакет |", "|---|---|---|---|"]
    for ext in index.extensionList.extensions:
        rows.append(f"| {ext.name} | {ext.versionName} | {ext.versionCode} | `{ext.packageName}` |")

    readme = "\n".join(
        [
            f"# {REPO_NAME}",
            "",
            "Персональный репозиторий расширений для Suwayomi / Mihon.",
            "",
            "## URL репозитория",
            "",
            "```",
            f"https://raw.githubusercontent.com/{REPO_NAME}/repo/index.pb",
            "```",
            "",
            "Старый формат (только `index.min.json`):",
            "",
            "```",
            f"https://raw.githubusercontent.com/{REPO_NAME}/repo/index.min.json",
            "```",
            "",
            "## Что внутри",
            "",
            *rows,
            "",
            f"- Исходники: https://github.com/{SOURCE_REPO}",
            f"- Отпечаток ключа подписи (signingKey): `{signing_key}`",
            "- Сборка и публикация — GitHub Actions в репозитории исходников.",
            "",
        ]
    )
    (REPO_DIR / "README.md").write_text(readme, encoding="utf-8")


def write_repo_files(index: index_pb2.Index, release_assets: dict) -> None:
    (REPO_DIR / "index.json").write_text(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        ),
        encoding="utf-8",
    )
    (REPO_DIR / "index.pb").write_bytes(
        gzip.compress(index.SerializeToString(deterministic=True), mtime=0)
    )

    repo_json = {
        "index_v2": f"https://github.com/{REPO_NAME}/raw/repo/index.pb",
        "meta": {
            "name": INDEX_NAME,
            "website": WEBSITE,
            "signingKeyFingerprint": signing_key,
        },
    }
    (REPO_DIR / "repo.json").write_text(
        json.dumps(repo_json, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    (REPO_DIR / "release-assets.json").write_text(
        json.dumps(release_assets, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    lines = [
        "<!DOCTYPE html>",
        "<html>",
        "<head>",
        '<meta charset="UTF-8">',
        f"<title>{html.escape(INDEX_NAME)}</title>",
        "</head>",
        "<body>",
        "<pre>",
    ]
    for ext in index.extensionList.extensions:
        lines.append(
            f'<a href="{html.escape(ext.resources.apkUrl)}">'
            f"{html.escape(f'Tachiyomi: {ext.name}')}</a>"
        )
    lines += ["</pre>", "</body>", "</html>", ""]
    (REPO_DIR / "index.html").write_text("\n".join(lines), encoding="utf-8")


def copy_apks(artifacts: list[tuple[dict, Path, Path]]) -> None:
    # Старый формат репозитория ожидает APK рядом с index.min.json: приложение склеивает
    # адрес ветки с именем файла. Кладём только текущие сборки, старые убираем.
    keep = set()
    for _, apk, _ in artifacts:
        keep.add(apk.name)
        (REPO_DIR / apk.name).write_bytes(apk.read_bytes())

    for stale in REPO_DIR.glob("*.apk"):
        if stale.name not in keep:
            stale.unlink()
            print(f"Удалён устаревший APK {stale.name}")


def main() -> None:
    artifacts = collect_artifacts()
    for info, apk, jar in artifacts:
        upload_release(info, apk, jar)

    index, release_assets = build_index(artifacts)
    write_repo_files(index, release_assets)
    write_readme(index)
    copy_apks(artifacts)

    (REPO_DIR / "index.min.json").write_text(
        json.dumps(build_legacy_index(artifacts), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    prune_releases({info["module"].split(".")[-1] for info, _, _ in artifacts})

    for ext in index.extensionList.extensions:
        print(f"Индекс: {ext.name} {ext.versionName} ({ext.versionCode}) → {ext.resources.apkUrl}")


if __name__ == "__main__":
    main()
