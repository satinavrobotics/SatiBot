export function initMap() {
    const mapStyle = [
        {
            "elementType": "geometry",
            "stylers": [
                {
                    "color": "#212121"
                }
            ]
        },
        {
            "elementType": "labels.icon",
            "stylers": [
                {
                    "visibility": "off"
                }
            ]
        },
        {
            "elementType": "labels.text.fill",
            "stylers": [
                {
                    "color": "#757575"
                }
            ]
        },
        {
            "elementType": "labels.text.stroke",
            "stylers": [
                {
                    "color": "#212121"
                }
            ]
        },
        {
            "featureType": "administrative",
            "elementType": "geometry",
            "stylers": [
                {
                    "color": "#757575"
                }
            ]
        },
        {
            "featureType": "administrative.country",
            "elementType": "labels.text.fill",
            "stylers": [
                {
                    "color": "#9e9e9e"
                }
            ]
        },
        {
            "featureType": "administrative.locality",
            "elementType": "labels.text.fill",
            "stylers": [
                {
                    "color": "#bdbdbd"
                }
            ]
        },
        {
            "featureType": "poi",
            "elementType": "labels.text.fill",
            "stylers": [
                {
                    "color": "#757575"
                }
            ]
        },
        {
            "featureType": "road",
            "elementType": "geometry",
            "stylers": [
                {
                    "color": "#424242"
                }
            ]
        },
        {
            "featureType": "water",
            "elementType": "geometry",
            "stylers": [
                {
                    "color": "#000000"
                }
            ]
        }
    ];

    const map = new google.maps.Map(document.getElementById("map"), {
        center: { lat: 47.4636, lng: 19.0402 }, // Default center (Budapest District XI)
        zoom: 15,
        styles: mapStyle // Apply the dark style
    });

    // Setup the Directions API components
    const directionsService = new google.maps.DirectionsService();
    const directionsRenderer = new google.maps.DirectionsRenderer({
        polylineOptions: { strokeColor: '#FF0000' } // Red route line
    });
    directionsRenderer.setMap(map);

    // Add a marker for the robot's current position
    const robotPosition = { lat: 47.4716, lng: 19.0502 }; // Hardcoded location
    const robotMarker = new google.maps.Marker({
        position: robotPosition,
        map: map,
        title: "Robot's Current Position",
        icon: 'http://maps.google.com/mapfiles/ms/icons/blue-dot.png' // Optional: custom icon
    });

    const missionList = document.getElementById("mission-list");
    const newMissionBtn = document.getElementById("new-mission-btn");
    const searchBox = new google.maps.places.SearchBox(document.getElementById("search-input"));
    const searchInput = document.getElementById("search-input");

    let isAddingMission = false;

    newMissionBtn.addEventListener("click", () => {
        isAddingMission = true;
        searchInput.style.display = 'block'; // Show the search box
        alert("Click on the map to add a new mission or use the search box.");
    });

    map.addListener("click", (event) => {
        if (isAddingMission) {
            addMission(event.latLng);
            isAddingMission = false;
            searchInput.style.display = 'none'; // Hide the search box
        }
    });

    // Bias the SearchBox results towards the current map's viewport.
    map.addListener("bounds_changed", () => {
        searchBox.setBounds(map.getBounds());
    });

    searchBox.addListener("places_changed", () => {
        if (!isAddingMission) return;

        const places = searchBox.getPlaces();
        if (places.length === 0) return;

        const place = places[0];
        if (!place.geometry || !place.geometry.location) {
            console.log("Returned place contains no geometry");
            return;
        }

        addMission(place.geometry.location);
        isAddingMission = false;
        searchInput.style.display = 'none'; // Hide the search box
    });

    function addMission(location) {
        // Create marker for the mission location.
        const marker = new google.maps.Marker({
            position: location,
            map: map,
            title: "Mission",
        });

        // Create a list item for the mission.
        const missionItem = document.createElement("li");
        missionItem.textContent = `Mission at (${location.lat().toFixed(2)}, ${location.lng().toFixed(2)}) `;

        // Create Delete Button.
        const deleteButton = document.createElement("button");
        deleteButton.textContent = "X";
        deleteButton.onclick = () => {
            missionItem.remove();
            marker.setMap(null);
        };

        // Create Go Button.
        const goButton = document.createElement("button");
        goButton.textContent = "Go";
        goButton.onclick = () => {
            // Query the directions API from the robot's current position to the mission location.
            directionsService.route({
                origin: robotPosition,
                destination: location,
                travelMode: google.maps.TravelMode.WALKING,
            }, (response, status) => {
                if (status === 'OK') {
                    directionsRenderer.setDirections(response);

                    // Place markers for each step in the route.
                    const steps = response.routes[0].legs[0].steps;
                    steps.forEach(step => {
                        new google.maps.Marker({
                            position: step.start_location,
                            map: map,
                            icon: {
                                path: google.maps.SymbolPath.CIRCLE,
                                scale: 5,
                                fillColor: '#FFFF00', // Yellow color
                                fillOpacity: 1,
                                strokeWeight: 0
                            },
                            title: "Step Marker"
                        });
                    });
                } else {
                    alert('Directions request failed due to ' + status);
                }
            });
        };

        // Append buttons to the mission item.
        missionItem.appendChild(deleteButton);
        missionItem.appendChild(goButton);
        missionList.appendChild(missionItem);
    }
}

function showTab(tab) {
    const videoTab = document.getElementById('video-tab');
    const mapTab = document.getElementById('map-tab');

    if (tab === 'video') {
        videoTab.classList.add('active');
        videoTab.classList.remove('inactive');
        mapTab.classList.add('inactive');
        mapTab.classList.remove('active');
        // Show video panel and hide map panel
        document.getElementById('video-panel').style.display = 'flex';
        document.getElementById('map-panel').style.display = 'none';
    } else {
        mapTab.classList.add('active');
        mapTab.classList.remove('inactive');
        videoTab.classList.add('inactive');
        videoTab.classList.remove('active');
        // Show map panel and hide video panel
        document.getElementById('map-panel').style.display = 'flex';
        document.getElementById('video-panel').style.display = 'none';
    }
}

// Attach showTab to the window object and make initMap globally accessible.
window.showTab = showTab;
window.initMap = initMap;

window.onload = () => {
    initMap();
};