# ldap-shepherd

A Clojure tool for user management on a LDAP server.

## Usage

Run the server in the "server" module with "lein ring server" (on port 3000)
Run figwheel with "lein figwheel"
Point your browser at http://localhost:


Wget POST/PUT test commands:

- new user
wget --server-response --method=POST --body-data='{ "uid" : "kdevries" }' --header="Content-Type: application/json" \
http://localhost:3000/users --http-user=admin --http-password=admin --output-document=-

- delete user
wget --server-response --method=DELETE --body-data='' --header="Content-Type: application/json" \
http://localhost:3000/users/kdevries --http-user=admin --http-password=admin --output-document=-

- new group
wget --server-response --method=POST --body-data='{ "gid" : "ubrijk" }' --header="Content-Type: application/json" \
http://localhost:3000/groups --output-document=-

- get users
wget --server-response --method=GET http://localhost:3000/users?q=* --output-document=-

- get groups
wget --server-response --method=GET http://localhost:3000/groups?q=* --output-document=-


## License

Copyright © 2015 Pieter van Prooijen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
