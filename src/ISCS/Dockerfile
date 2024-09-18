# Use an official Python runtime as a parent image
FROM python:3.8-slim

# Set the working directory in the container
WORKDIR /app

# Copy the current directory contents into the container at /app
COPY ./src/ISCS/iscs.py /app

# Install any needed packages specified in requirements.txt
# If you don't have a requirements.txt, Flask and requests will be installed directly
RUN pip install --no-cache-dir Flask requests

# Run iscs.py when the container launches
CMD ["python", "iscs.py"]
