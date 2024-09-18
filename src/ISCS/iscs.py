from flask import Flask, request, jsonify
import requests
import itertools

app = Flask(__name__)

# List of user service instances
user_services = ['http://userservice1:13001', 'http://userservice2:13003']
service_cycle = itertools.cycle(user_services)

@app.route('/user/', methods=['GET', 'POST'])
def user_proxy():
    print("Received a request to /user/")
    print(f"Request Method: {request.method}")
    print(f"Request URL: {request.url}")
    print(f"Request Headers: {request.headers}")
    print(f"Request Data: {request.get_data()}")  # Request data for POST requests
    if request.json:
        print(f"JSON Data: {request.json}")  # JSON data for POST requests
    service_url = next(service_cycle) + '/user/'
    print(f"Forwarding request to: {service_url}")

    if request.method == 'POST':
        print(f"Request Body: {request.json}")
        response = requests.post(service_url, json=request.json)
    else:
        print(f"Query Params: {request.args}")
        response = requests.get(service_url, params=request.args)

    return jsonify(response.json()), response.status_code

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0')

