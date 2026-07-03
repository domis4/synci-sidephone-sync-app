const state = {
    target: "photos",
    items: []
};

const tabs = Array.from(document.querySelectorAll(".tab"));
const content = document.getElementById("content");
const dropZone = content;
const viewTitle = document.getElementById("viewTitle");
const refreshButton = document.getElementById("refreshButton");
const fileInput = document.getElementById("fileInput");
const playlistSection = document.getElementById("playlistSection");
const playlists = document.getElementById("playlists");

const photoTemplate = document.getElementById("photoTemplate");
const musicTemplate = document.getElementById("musicTemplate");
const fileTemplate = document.getElementById("fileTemplate");

function titleForTarget(target) {
    return {
        photos: "Photos",
        music: "Music",
        documents: "Documents",
        contacts: "Contacts",
        other: "Other"
    }[target] || "Library";
}

function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return "0 B";

    const units = ["B", "KB", "MB", "GB"];
    let value = bytes;
    let index = 0;

    while (value >= 1024 && index < units.length - 1) {
        value = value / 1024;
        index++;
    }

    return value.toFixed(index === 0 ? 0 : 1) + " " + units[index];
}

function formatDuration(ms) {
    if (!ms || ms <= 0) return "";

    const totalSeconds = Math.round(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;

    return minutes + ":" + String(seconds).padStart(2, "0");
}

function downloadUrl(item) {
    return "/download?target=" + encodeURIComponent(item.target) +
        "&id=" + encodeURIComponent(item.id);
}

function previewUrl(item) {
    return "/preview?target=" + encodeURIComponent(item.target) +
        "&id=" + encodeURIComponent(item.id);
}

function albumArtUrl(item) {
    return "/art?id=" + encodeURIComponent(item.id) +
        "&albumId=" + encodeURIComponent(item.albumId || 0);
}

async function setTarget(target) {
    state.target = target;

    tabs.forEach(tab => {
        tab.classList.toggle("active", tab.dataset.target === target);
    });

    viewTitle.textContent = titleForTarget(target);
    await loadLibrary();
}

async function loadLibrary() {
    content.className = state.target === "photos"
        ? "content-grid photos-grid"
        : "content-grid " + (state.target === "music" ? "music-list" : "file-list");

    content.setAttribute("data-drop-label", "Drop to add");
    content.innerHTML = `<div class="empty">Loading</div>`;

    const response = await fetch("/api/list?target=" + encodeURIComponent(state.target));
    const json = await response.json();

    if (!response.ok || !json.ok) {
        content.innerHTML = `<div class="empty">Could not load</div>`;
        return;
    }

    state.items = json.items || [];
    renderItems();

    if (state.target === "music") {
        await loadPlaylists();
    } else {
        playlistSection.classList.add("hidden");
    }
}

function renderItems() {
    content.innerHTML = "";

    if (state.items.length === 0) {
        content.innerHTML = `<div class="empty">No items — drop files here</div>`;
        return;
    }

    for (const item of state.items) {
        if (state.target === "photos") {
            renderPhoto(item);
        } else if (state.target === "music") {
            renderMusic(item);
        } else {
            renderFile(item);
        }
    }
}

function renderPhoto(item) {
    const node = photoTemplate.content.cloneNode(true);
    const card = node.querySelector(".photo-card");
    const img = node.querySelector(".photo-img");
    const download = node.querySelector(".download");
    const del = node.querySelector(".delete");

    img.src = previewUrl(item);
    img.alt = item.fileName;
    download.href = downloadUrl(item);
    del.addEventListener("click", () => deleteItem(item));

    content.appendChild(card);
}

function renderMusic(item) {
    const node = musicTemplate.content.cloneNode(true);
    const row = node.querySelector(".music-row");
    const cover = node.querySelector(".cover");
    const title = node.querySelector(".music-title");
    const meta = node.querySelector(".music-meta");
    const download = node.querySelector(".download");
    const del = node.querySelector(".delete");

    cover.src = albumArtUrl(item);
    cover.onerror = () => {
        cover.removeAttribute("src");
    };

    title.textContent = item.title || item.fileName;

    const duration = formatDuration(item.duration);
    const details = [item.artist, item.album, duration, formatBytes(item.size)]
        .filter(Boolean)
        .join(" · ");

    meta.textContent = details || item.fileName;

    download.href = downloadUrl(item);
    del.addEventListener("click", () => deleteItem(item));

    content.appendChild(row);
}

function renderFile(item) {
    const node = fileTemplate.content.cloneNode(true);
    const row = node.querySelector(".file-row");
    const title = node.querySelector(".file-title");
    const meta = node.querySelector(".file-meta");
    const download = node.querySelector(".download");
    const del = node.querySelector(".delete");

    title.textContent = item.fileName;
    meta.textContent = [item.mimeType, formatBytes(item.size), item.source]
        .filter(Boolean)
        .join(" · ");

    download.href = downloadUrl(item);
    del.addEventListener("click", () => deleteItem(item));

    content.appendChild(row);
}

async function loadPlaylists() {
    playlistSection.classList.remove("hidden");
    playlists.innerHTML = "";

    const response = await fetch("/api/playlists");
    const json = await response.json();

    if (!response.ok || !json.ok || !json.items || json.items.length === 0) {
        playlists.innerHTML = `<div class="playlist-card">No playlists</div>`;
        return;
    }

    for (const playlist of json.items) {
        const card = document.createElement("div");
        card.className = "playlist-card";
        card.textContent = playlist.name;
        playlists.appendChild(card);
    }
}

async function deleteItem(item) {
    const ok = window.confirm("Delete from Sidephone?\n\n" + item.fileName);
    if (!ok) return;

    const response = await fetch(
        "/delete?target=" + encodeURIComponent(item.target) +
        "&id=" + encodeURIComponent(item.id),
        { method: "DELETE" }
    );

    const json = await response.json();

    if (!response.ok || !json.ok) {
        alert(json.message || "Delete failed");
        return;
    }

    if (json.pending) {
        alert("Confirm delete on Sidephone, then refresh.");
        return;
    }

    await loadLibrary();
}

async function uploadFiles(files) {
    const list = Array.from(files);
    if (list.length === 0) return;

    content.classList.add("dragging");
    content.setAttribute("data-drop-label", "Uploading");

    try {
        for (const file of list) {
            const data = new FormData();
            data.append("target", state.target);
            data.append("file", file, file.name);

            const response = await fetch("/upload", {
                method: "POST",
                body: data
            });

            const json = await response.json();

            if (!response.ok || !json.ok) {
                throw new Error(json.message || "Upload failed");
            }
        }

        await loadLibrary();
    } catch (error) {
        alert(error.message);
    } finally {
        content.classList.remove("dragging");
        content.setAttribute("data-drop-label", "Drop to add");
        fileInput.value = "";
    }
}

function preventDefaults(event) {
    event.preventDefault();
    event.stopPropagation();
}

tabs.forEach(tab => {
    tab.addEventListener("click", () => setTarget(tab.dataset.target));
});

refreshButton.addEventListener("click", loadLibrary);

fileInput.addEventListener("change", () => {
    uploadFiles(fileInput.files);
});

["dragenter", "dragover"].forEach(eventName => {
    content.addEventListener(eventName, event => {
        preventDefaults(event);
        content.classList.add("dragging");
        content.setAttribute("data-drop-label", "Drop to add");
    });
});

["dragleave", "drop"].forEach(eventName => {
    content.addEventListener(eventName, event => {
        preventDefaults(event);
        content.classList.remove("dragging");
        content.setAttribute("data-drop-label", "Drop to add");
    });
});

content.addEventListener("drop", event => {
    uploadFiles(event.dataTransfer.files);
});

setTarget("photos");