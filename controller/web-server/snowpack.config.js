const proxy = require('http2-proxy')
const dotenv = require('dotenv');

dotenv.config();

/** @type {import("snowpack").SnowpackUserConfig } */
module.exports = {
    mount: {
        "client": "/"
    },
    optimize: {
        bundle: true,
        minify: true,
        target: 'es2018',
        treeshake: true,
    },
    routes: [
        /*{
            
            src: '/ws',
            upgrade: (req, socket, head) => {

                const defaultWSHandler = (err, req, socket, head) => {
                    if (err) {
                        console.error('proxy error', err);
                        socket.destroy();
                    }
                };

                proxy.ws(
                    req,
                    socket,
                    head,
                    {
                        hostname: 'localhost',
                        port: 7071,
                    },
                    defaultWSHandler,
                );
            },
            
        },*/

        {
            src: '/api/.*', // Proxy all API requests
            dest: (req, res) => {
                proxy.web(
                    req,
                    res,
                    {
                        hostname: 'localhost',
                        port: 8080, // Forward to HTTP server
                    },
                    (err) => {
                        if (err) {
                            console.error('HTTP proxy error', err);
                            if (!res.headersSent) {
                                res.writeHead(500);
                                res.end('Proxy Error');
                            } 
                        }
                    }
                );
            }
        }
    ],
};
