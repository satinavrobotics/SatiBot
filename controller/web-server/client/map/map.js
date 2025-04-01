export class MapController {
    constructor() {
        this.mapContainer = document.getElementById("map");
        this.mapStyle = this.getMapStyle();
        this.map = this.initMap();
        this.directionsService = new google.maps.DirectionsService();
        this.directionsRenderer = this.initDirectionsRenderer();
        this.robotMarker = null;
        this.robotPosition = null;
        this.missionList = document.getElementById("mission-list");
        this.newMissionBtn = document.getElementById("new-mission-btn");
        this.searchInput = document.getElementById("search-input");
        this.isAddingMission = false;
        this.setupEventListeners();
        this.initAutocomplete();
        window.startLocationService = (connection) => {
            setInterval(async () => {
                const results = await connection.getLocation();
                this.updateRobotPosition(results);
            }, 1000);
        }
    }

    getMapStyle() {
        return [
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
    }

    initMap() {
        return new google.maps.Map(this.mapContainer, {
            center: { lat: 47.4636, lng: 19.0402 }, // Default center (Budapest District XI)
            zoom: 15,
            styles: this.mapStyle // Apply the dark style
        });
    }

    initDirectionsRenderer() {
        const renderer = new google.maps.DirectionsRenderer({
            polylineOptions: { strokeColor: '#FF0000' } // Red route line
        });
        renderer.setMap(this.map);
        return renderer;
    }

    async initAutocomplete() {
        // Request the necessary libraries.
        const { AutocompleteSessionToken, AutocompleteSuggestion } =
            await google.maps.importLibrary("places");

        // Create a session token.
        this.sessionToken = new AutocompleteSessionToken();

        // Add event listener to the search input.
        this.searchInput.addEventListener("input", async (event) => {
            const input = event.target.value;
            if (!input) return;

            // Fetch autocomplete suggestions.
            const request = {
                input: input,
                locationBias: new google.maps.LatLng(47.4979, 19.0402), // Coordinates of Budapest city center
                includedPrimaryTypes: ["restaurant"], // Restrict results to restaurants
                language: "hu-HU",
                region: "HU", // Region code for Hungary
                sessionToken: this.sessionToken,
              };

            const { suggestions } =
                await AutocompleteSuggestion.fetchAutocompleteSuggestions(request);

            // Display suggestions in a dropdown.
            this.displaySuggestions(suggestions);
        });
    }

    displaySuggestions(suggestions) {
        const suggestionsContainer = document.getElementById("suggestions-container");
        suggestionsContainer.innerHTML = ""; // Clear previous suggestions.

        if (suggestions.length > 0) {
            suggestionsContainer.style.display = 'block'; // Show the suggestions container
        } else {
            suggestionsContainer.style.display = 'none'; // Hide if no suggestions
        }

        suggestions.forEach((suggestion) => {
            const suggestionElement = document.createElement("div");
            suggestionElement.textContent = suggestion.placePrediction.text.toString();
            suggestionElement.addEventListener("click", async () => {
                const place = suggestion.placePrediction.toPlace();
                await place.fetchFields({
                    fields: ["displayName", "formattedAddress", "location"],
                });

                // Add the selected place as a mission.
                this.addMission(place.location);
                this.isAddingMission = false;
                this.searchInput.style.display = 'none'; // Hide the search box
                suggestionsContainer.style.display = 'none'; // Hide suggestions container
            });

            suggestionsContainer.appendChild(suggestionElement);
        });
    }

    setupEventListeners() {
        this.newMissionBtn.addEventListener("click", () => {
            this.isAddingMission = true;
            this.searchInput.style.display = 'block'; // Show the search box
        });

        this.map.addListener("click", (event) => {
            if (this.isAddingMission) {
                this.addMission(event.latLng);
                this.isAddingMission = false;
                this.searchInput.style.display = 'none'; // Hide the search box
            }
        });
    }

    updateRobotPosition(newPosition) {
        if (this.robotMarker) {
            this.robotMarker.setPosition(newPosition);
            this.map.setCenter(newPosition);
        } else {
            this.robotMarker = new google.maps.Marker({
                position: newPosition,
                map: this.map,
                title: "Robot's Current Position",
                icon: 'http://maps.google.com/mapfiles/ms/icons/blue-dot.png'
            });
        }
        this.robotPosition = newPosition;
    }

    addMission(location) {
        const marker = new google.maps.Marker({
            position: location,
            map: this.map,
            title: "Mission",
        });

        const missionItem = document.createElement("li");
        missionItem.textContent = `Mission at (${location.lat().toFixed(2)}, ${location.lng().toFixed(2)}) `;
        
        // Set the width of the mission item
        missionItem.style.width = '100%'; // Adjust the width as needed

        const deleteButton = document.createElement("button");
        deleteButton.textContent = "X";
        deleteButton.onclick = () => {
            missionItem.remove();
            marker.setMap(null);
        };

        const goButton = document.createElement("button");
        goButton.textContent = "Go";
        goButton.classList.add("go-button");
        goButton.onclick = () => {
            if (!this.robotPosition) {
                alert("Please set the robot's position first.");
                return;
            }
            this.directionsService.route({
                origin: this.robotPosition,
                destination: location,
                travelMode: google.maps.TravelMode.WALKING,
            }, (response, status) => {
                if (status === 'OK') {
                    this.directionsRenderer.setDirections(response);
                    this.addStepMarkers(response);
                } else {
                    alert('Directions request failed due to ' + status);
                }
            });
        };

        missionItem.appendChild(deleteButton);
        missionItem.appendChild(goButton);
        this.missionList.appendChild(missionItem);
    }

    addStepMarkers(response) {
        const steps = response.routes[0].legs[0].steps;
        steps.forEach(step => {
            new google.maps.Marker({
                position: step.start_location,
                map: this.map,
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
    }
}

document.addEventListener("DOMContentLoaded", () => {
    //const mapController = new MapController();
    //console.log("Map loaded");
});