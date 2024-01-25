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
    imageSizeChanged();
    updateFrame();
}

function frameImageUrl(cameraId) {
    const quality = parseInt(document.getElementById('qualityDropdown').value)
    const size = document.getElementById('imageSizeDropdown').value.split('x');
    const ts = new Date().getTime();
    return `cam/${cameraId}/frame?q=${quality}&w=${size[0]}&h=${size[1]}&ts=${ts}`;
}

function updateFrame() {
    const cameraViewContainer = document.getElementById('cameraViewContainer');
    if (cameraViewContainer.style.display === 'block') {
        const imageElement = document.getElementById('cameraView');
        imageElement.src = frameImageUrl(currentTab);
    }
    const fpsDropdown = document.getElementById('fpsDropdown');
    const interval= 1000 / parseInt(fpsDropdown.value)
    setTimeout(updateFrame, interval);
}

function changeCameraTab(tabNumber) {
    inactivateAllTabs();
    showElementById('cameraViewContainer');
    activateTabById(`cameraTab${tabNumber}`);

    const cameraView = document.getElementById('cameraView');
    cameraView.src = frameImageUrl(tabNumber);
    currentTab = tabNumber;
}

function activateJsonEditor() {
    inactivateAllTabs();
    showElementById('jsonEditorContainer');
    activateTabById('editConfigButton');

    fetch('config/edit')
        .then(response => response.json())
        .then(data => {
            jsonEditor.setValue(JSON.stringify(data, null, 2));
        });
}

function showListDevices() {
    inactivateAllTabs();
    showElementById('listDevicesContainer');
    activateTabById('listDevicesButton');

    fetch('list-devices')
        .then(response => response.text())
        .then(data => {
            document.getElementById('listDevicesResult').innerHTML = data;
        });
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

    const tabContainers = document.getElementsByClassName('tabContainer');
    for (let i = 0; i < tabContainers.length; i++) {
        tabContainers[i].style.display = 'none';
    }
}

function activateTabById(id) {
    const element = document.getElementById(id);
    if (element) {
        element.classList.add('active-tab');
    }
}

function imageSizeChanged() {
    const width = document.getElementById('imageSizeDropdown').value.split('x')[0];
    document.getElementById('jsonEditorContainer').style.width = `${width}px`;
    document.getElementById('cameraViewContainer').style.width = `${width}px`;
}

function setCameraParam() {
    const paramId = document.getElementById('paramId').value;
    const paramValue = document.getElementById('paramValue').value;

    fetch(`cam/${currentTab}/parameter?id=${paramId}&value=${paramValue}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain',
        }
    })
        .then(response => response.text())
        .then(data => {
            document.getElementById('testResult').value = data;
        });
}

function getCameraParam() {
    const paramId = document.getElementById('paramId').value;

    fetch(`cam/${currentTab}/parameter?id=${paramId}`)
        .then(response => response.text())
        .then(data => {
            if (data.endsWith('.0')) {
                data = data.substring(0, data.length - 2);
            }
            document.getElementById('testResult').value = data;
        });
}

