let currentTab = 0;
updateFrameInterval = setInterval(updateFrame, 1000);
let config = {};

// Fetch tab data from the server
fetch('homevision/config')
  .then(response => response.json())
  .then(data => {
    config = data;
    initializeTabs();
  })
  .catch(error => console.error('Error fetching tab data:', error));

function initializeTabs() {
  const tabsContainer = document.getElementById('tabs-container');
  const imageContainer = document.getElementById('image-container');

  for (let i = 0; i < config.length; i++) {
    const button = document.createElement('button');
    button.textContent = config[i].name;
    button.addEventListener('click', () => changeTab(i));
    tabsContainer.appendChild(button);

    const img = document.createElement('img');
    img.id = `image${i}`;
    img.className = 'tab';
    img.src = frameImageUrl(i);
    img.alt = `Camera ${i}`;
    imageContainer.appendChild(img);
  }

  changeTab(0);
}

function frameImageUrl(cameraId) {
    const quality = parseInt(document.getElementById('qualitySlider').value)
    const ts = new Date().getTime();

    return `homevision/cam/${cameraId}/frame?q=${quality}&ts=${ts}`;
}

function updateFrame() {
    for (let i = 0; i < config.length; i++) {
        const imageElement = document.getElementById(`image${i}`);
        if (imageElement.classList.contains('active-tab')) {
            imageElement.src = frameImageUrl(i);
        }
    }
}

function changeTab(tabNumber) {
  const imageElement = document.getElementById(`image${tabNumber}`);

  imageElement.classList.remove('active-tab');
  imageElement.classList.add('active-tab');

  imageElement.src = frameImageUrl(tabNumber);
  currentTab = tabNumber;
  updateFrame();
}

function changePreviewFps() {
    const intervalSlider = document.getElementById('intervalSlider');
    const intervalValue = document.getElementById('intervalValue');

    fps = parseInt(intervalSlider.value);
    intervalValue.textContent = fps;

    clearInterval(updateFrameInterval);
    updateFrameInterval = setInterval(updateFrame, 1000 / fps);
    updateFrame();
}

function updateQualityLabel(value) {
    document.getElementById('qualityValue').textContent = value;
}

function toggleJsonEditor() {
  const jsonEditorContainer = document.getElementById('jsonEditorContainer');
  const editConfigButton = document.getElementById('editConfigButton');

  if (jsonEditorContainer.style.display === 'none') {
    jsonEditorContainer.style.display = 'block';

    // Load JSON content from an endpoint (replace 'your-endpoint' with the actual URL)
    fetch('homevision/config/edit')
      .then(response => response.json())
      .then(data => {
        jsonEditor.setValue(JSON.stringify(data, null, 2));
      });

  } else {
    jsonEditorContainer.style.display = 'none';
  }
}

function saveJson() {
  const editedJson = jsonEditor.getValue();
  fetch('homevision/config/edit', {
      method: 'POST',
      headers: {
        'Content-Type': 'text/plain',
      },
      body: editedJson,
    })
      .then(data => {
        console.log('Successfully saved:', data);
        toggleJsonEditor();
      });

}

