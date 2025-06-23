(function () {
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const selectFilesBtn = document.getElementById('select-files-btn');
    const fileListUl = document.getElementById('file-list');
    const uploadBtn = document.getElementById('upload-btn');

    let filesQueue = [];

    function renderFileList() {
        fileListUl.innerHTML = '';
        filesQueue.forEach((file, idx) => {
            const li = document.createElement('li');
            li.textContent = file.name;

            const removeBtn = document.createElement('button');
            removeBtn.textContent = '✖';
            removeBtn.classList.add('remove-btn');
            removeBtn.addEventListener('click', () => {
                filesQueue.splice(idx, 1);
                renderFileList();
                uploadBtn.disabled = filesQueue.length === 0;
            });

            li.appendChild(removeBtn);
            fileListUl.appendChild(li);
        });
    }

    function addFiles(newFiles) {
        for (let f of newFiles) {
            const exists = filesQueue.some(existing =>
                existing.name === f.name && existing.size === f.size
            );
            if (!exists) {
                filesQueue.push(f);
            }
        }
        renderFileList();
        uploadBtn.disabled = filesQueue.length === 0;
    }

    async function uploadAllFiles() {
        if (filesQueue.length === 0) return;
        uploadBtn.disabled = true;
        for (let file of filesQueue) {
            const formData = new FormData();
            formData.append('file', file);
            try {
                await fetch('/upload', {
                    method: 'POST',
                    body: formData
                });
            } catch (err) {
                console.error('Error uploading file', file.name, err);
            }
        }
        window.location.reload();
    }

    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.classList.add('hover');
    });
    dropZone.addEventListener('dragleave', (e) => {
        e.preventDefault();
        dropZone.classList.remove('hover');
    });
    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('hover');
        const dt = e.dataTransfer;
        if (dt && dt.files && dt.files.length) {
            addFiles(dt.files);
        }
    });

    selectFilesBtn.addEventListener('click', () => {
        fileInput.click();
    });
    fileInput.addEventListener('change', (e) => {
        addFiles(e.target.files);
        fileInput.value = '';
    });

    uploadBtn.addEventListener('click', () => {
        uploadAllFiles();
    });

    uploadBtn.disabled = true;
})();