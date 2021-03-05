package com.sailheader.middleware.service.impl

import groovy.json.JsonSlurper

class ImportDataJson {
    static def slurper = new JsonSlurper()
    static def normal = slurper.parseText("""
    {
        "action": [
            {
                "id": 1,
                "job_id": 2,
                "name": "steven",
                "status": 1,
                "type": 1,
                "index": 3,
                "consume_data_type": 1,
                "created_by": 1,
                "addition": {"test":"test"},
                "created_on": null,
                "modified_by": null,
                "modified_on": null
            }
        ]
    }
    """)
}
