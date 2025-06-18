import requests
from requests.auth import HTTPBasicAuth
import sys

# Ensure an argument is passed
if len(sys.argv) < 2:
    print("Usage: python script.py <participantName>")
    sys.exit(1)

# CLI argument
participant_name = sys.argv[1]

# Target URL and credentials
target_url = 'https://controller.satinavrobotics.com/api/createToken'
username = 'satiadmin'
password = 'poganyindulo'

# JSON body
body = {
    "roomName": "admin@satinavrobotics.com",
    "participantName": participant_name
}

# Send POST request
response = requests.post(target_url, auth=HTTPBasicAuth(username, password), json=body)

# Print response
print(response.text)

