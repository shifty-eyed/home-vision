let currentTab = 0;
let config = {};

// Fetch tab data from the server
fetch('config')
    .then(response => response.json())
    .then(data => {
        config = data;
        initializeTabs();
    })
    .catch(error => console.error('Error fetching tab data:', error));

function initializeTabs() {
    const tabsContainer = document.getElementById('tabsContainer');
    while (tabsContainer.firstChild) {
        tabsContainer.removeChild(tabsContainer.firstChild);
    }

    for (let i = 0; i < config.length; i++) {
        const button = document.createElement('button');
        button.id = `cameraTab${i}`;
        button.textContent = config[i].name;
        button.addEventListener('click', () => changeCameraTab(i));
        button.className = 'tab';
        tabsContainer.appendChild(button);
    }

    currentTab = 0;
    changeCameraTab(currentTab);
    updateFrame();
}

function frameImageUrl(cameraId) {
    const quality = parseInt(document.getElementById('qualitySlider').value)
    const ts = new Date().getTime();
    return `cam/${cameraId}/frame?q=${quality}&ts=${ts}`;
}

function updateFrame() {
    const cameraViewContainer = document.getElementById('cameraViewContainer');
    if (cameraViewContainer.style.display === 'block') {
        const imageElement = document.getElementById('cameraView');
        imageElement.src = frameImageUrl(currentTab);
    }
    const intervalSlider = document.getElementById('intervalSlider');
    setTimeout(updateFrame, parseInt(intervalSlider.value));
}

function changeCameraTab(tabNumber) {
    inactivateAllTabs();
    showElementById('cameraViewContainer');
    hideElementById('jsonEditorContainer');

    activateTabById(`cameraTab${tabNumber}`);

    const cameraView = document.getElementById('cameraView');
    cameraView.src = frameImageUrl(tabNumber);
    currentTab = tabNumber;
}

function activateJsonEditor() {
    inactivateAllTabs();
    hideElementById('cameraViewContainer');
    showElementById('jsonEditorContainer');
    activateTabById('jsonEditorTab');

    fetch('config/edit')
        .then(response => response.json())
        .then(data => {
            jsonEditor.setValue(JSON.stringify(data, null, 2));
        });
}

function changePreviewFps() {
    const intervalSlider = document.getElementById('intervalSlider');
    const intervalValue = document.getElementById('intervalValue');

    fps = parseInt(intervalSlider.value);
    intervalValue.textContent = fps;
}

function updateQualityLabel(value) {
    document.getElementById('qualityValue').textContent = value;
}


function saveJson() {
    const editedJson = jsonEditor.getValue();
    fetch('config/edit', {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain',
        },
        body: editedJson,
    })
        .then(data => {
            console.log('Successfully saved:', data);
            location.reload();
        });
}

function hideElementById(id) {
    const element = document.getElementById(id);
    if (element) {
        element.style.display = 'none';
    }
}

function showElementById(id) {
    const element = document.getElementById(id);
    if (element) {
        element.style.display = 'block';
    }
}

function inactivateAllTabs() {
    const elements = document.getElementsByClassName('tab');
    for (let i = 0; i < elements.length; i++) {
        elements[i].classList.remove('active-tab');
    }
}

function activateTabById(id) {
    const element = document.getElementById(id);
    if (element) {
        element.classList.add('active-tab');
    }
}

