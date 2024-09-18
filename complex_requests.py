import requests
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

# Base URL for the API
BASE_URL = "http://127.0.0.1:14002"

# Number of workers in the ThreadPool
NUM_WORKERS = 10

# Total number of user and product requests
TOTAL_REQUESTS = 1000

# Function to generate user data
def generate_user_data(start_id, end_id):
    user_data = []
    for i in range(start_id, end_id):
        user = {
            "command": "create",
            "id": i,
            "username": f"tester{i}",
            "email": f"test{i}@test.com",
            "password": f"password{i}"
        }
        user_data.append(user)
    return user_data

# Function to generate product data
def generate_product_data(start_id, end_id):
    product_data = []
    for i in range(start_id, end_id):
        product = {
            "command": "create",
            "id": i,
            "name": f"productname-{i}",
            "description": f"test-{i}",
            "price": 3.99,
            "quantity": 9
        }
        product_data.append(product)
    return product_data

# Function to send POST request to create a user
def create_user(user):
    response = requests.post(f"{BASE_URL}/user", json=user)
    if response.status_code != 200:
        print(f"Failed to create user {user['id']}: {response.status_code}")
    return response

# Function to send GET request to retrieve user information
def get_user_info(user):
    response = requests.get(f"{BASE_URL}/user/{user['id']}")
    if response.status_code != 200:
        print(f"Failed to retrieve user {user['id']}: {response.status_code}")
    return response

# Function to send POST request to create a product
def create_product(product):
    response = requests.post(f"{BASE_URL}/product", json=product)
    if response.status_code != 200:
        print(f"Failed to create product {product['id']}: {response.status_code}")
    return response

# Function to send DELETE request to delete a user
def delete_user(user):
    # Here, I'm assuming the DELETE endpoint expects a JSON body with user details
    response = requests.delete(f"{BASE_URL}/user/{user['id']}", json={"username": user['username'], "email": user['email'], "password": user['password']})
    if response.status_code != 200:
        print(f"Failed to delete user {user['id']}: {response.status_code}")
    return response

# Function to perform user and product creation, retrieval, and deletion for a range of IDs
def handle_requests(start_id, end_id):
    user_data = generate_user_data(start_id, end_id)
    product_data = generate_product_data(start_id, end_id)
    for user, product in zip(user_data, product_data):
        create_user(user)
        create_product(product)
        get_user_info(user)
        get_product_info(product)
    
    # Delete every other user
    for i, user in enumerate(user_data):
        if i % 2 == 0:  # Change this condition to delete users based on your criteria
            delete_user(user)

# Function to send GET request to retrieve product information
def get_product_info(product):
    response = requests.get(f"{BASE_URL}/product/{product['id']}")
    if response.status_code != 200:
        print(f"Failed to retrieve product {product['id']}: {response.status_code}")
    return response
# Main function to orchestrate the operations
def main():
    start_time = time.time()

    ids_per_worker = TOTAL_REQUESTS // NUM_WORKERS

    with ThreadPoolExecutor(max_workers=NUM_WORKERS) as executor:
        futures = []
        for i in range(NUM_WORKERS):
            start_id = 1000 + i * ids_per_worker
            end_id = start_id + ids_per_worker
            futures.append(executor.submit(handle_requests, start_id, end_id))
        
        for future in as_completed(futures):
            future.result()

    end_time = time.time()

    # Calculating requests per second
    total_requests = TOTAL_REQUESTS * 4  # Each ID has 4 operations (user create, product create, user get, product get), not counting deletes
    print(f"Total time for {total_requests} requests: {end_time - start_time} seconds")
    print(f"Requests per second: {total_requests / (end_time - start_time)}")

if __name__ == "__main__":
    main()
