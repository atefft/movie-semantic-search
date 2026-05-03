import re

HTML_PATH = "docs/executive-guide.html"


def read_html():
    with open(HTML_PATH) as f:
        return f.read()


def test_no_old_docker_commands():
    html = read_html()
    assert "docker compose run --rm load-model" not in html
    assert "docker compose up -d" not in html
    assert "docker compose run --rm load-data" not in html


def test_rebuild_flag_present_twice():
    html = read_html()
    assert html.count("start-services.sh --rebuild") >= 2


def test_no_flag_startup_mentioned():
    html = read_html()
    assert "start-services.sh" in html  # no-flag variant mentioned


def test_stop_services_present():
    html = read_html()
    assert "stop-services.sh" in html
    assert "stop-services.sh --clean" in html


def test_stopping_services_section():
    html = read_html()
    assert 'id="stopping-services"' in html


def test_toc_stopping_services_link():
    html = read_html()
    assert '#stopping-services' in html


def test_uat_note_present():
    html = read_html()
    assert "/uat" in html


def test_preserved_content():
    html = read_html()
    assert "TMDB" in html
    assert "localhost:6333" in html
    assert "localhost:8080" in html
    assert "docker.com" in html or "docker.com/products" in html
