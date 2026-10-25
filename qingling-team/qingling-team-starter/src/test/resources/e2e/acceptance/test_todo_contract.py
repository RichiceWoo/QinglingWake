"""Harness-owned Todo acceptance contract; product roles must not modify this file."""

import re

from fastapi.testclient import TestClient

from main import app


def _items(response):
    assert response.status_code == 200, response.text
    payload = response.json()
    assert isinstance(payload, list), payload
    return payload


def _create(client, title):
    response = client.post("/todos", json={"title": title})
    assert response.status_code == 201, response.text
    payload = response.json()
    assert isinstance(payload.get("id"), int), payload
    assert payload.get("title") == title, payload
    return payload


def test_todo_crud_descending_and_persistence():
    with TestClient(app) as client:
        first = _create(client, "contract-first")
        second = _create(client, "contract-second")
        listed = _items(client.get("/todos"))
        ids = [item["id"] for item in listed]
        assert ids == sorted(ids, reverse=True), ids
        assert ids.index(second["id"]) < ids.index(first["id"])

        completed = client.patch(f"/todos/{first['id']}/complete")
        assert completed.status_code == 200, completed.text
        assert completed.json().get("completed") is True

        deleted = client.delete(f"/todos/{second['id']}")
        assert deleted.status_code == 204, deleted.text
        assert client.get(f"/todos/{second['id']}").status_code == 404

    with TestClient(app) as restarted:
        persisted = _items(restarted.get("/todos"))
        assert any(item["id"] == first["id"] for item in persisted), persisted


def test_todo_boundaries_and_single_file_html():
    with TestClient(app) as client:
        assert client.get("/todos/2147483647").status_code == 404
        assert client.post("/todos", json={"title": "   "}).status_code == 422
        page = client.get("/")
        assert page.status_code == 200, page.text
        html = page.text
        assert "<html" in html.lower()
        assert not re.search(r'<script[^>]+src=["\'](?!https?://|//)', html, re.I)
        assert not re.search(r'<link[^>]+href=["\'](?!https?://|//)[^"\']+\.css', html, re.I)
