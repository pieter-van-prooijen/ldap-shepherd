wget --server-response  --header="Content-Type: application/json" --http-user=admin --http-password=admin --output-document=out.json --method=$1 $3 "http://localhost:3000$2"
python -mjson.tool < out.json

