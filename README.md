# Description
MxDBUnit is a testing framework (an extension for the 'Unit Testing' module) designed to facilitate unit testing for database read and write operations in Mendix.  

To begin with, please check the contents within the StudioPro `_Samples` folder and the sample Excel files located in the `App/resources/mxdbunit` folder of the file system.    
## Key Features and Functions
### Easy Test Data Preparation:  
Test data for entities can be defined in Excel.  
While Mendix automatically assigns physical IDs upon registration, you can freely define unique logical IDs in your Excel sheets. MxDBUnit dynamically maps these logical IDs to physical IDs, accurately replicating Excel-based associations within the database.
### Database State Reset:  
Before test execution, data for multiple entities defined in the Excel file is loaded into the database in a single operation.  
This clears or initializes the data beforehand, ensuring that each test begins from a clean state.
### Comparison with Expected Values:  
Easily verifies (via assertions) whether the database contents after program execution match the expected results, using data defined in an Excel file.  
By converting entity data into text and comparing it using java-diff-utils, the system accurately presents the overall differences in a format that is easy for the person conducting the test to understand.
### External Database Testing (v1.1.0 or later):
Testing is supported for external databases in addition to internal databases.
For applications using TwoWaySQL (v2.7.0 or later), you can insert test data into an external database and compare the results against expected outcomes using the same procedures as for internal database testing.
# Rules for Test Data Excel Files
### Basic Rules:
- MxDBUnit treats sheets with names starting with "#" as documentation and ignores them.  
- MxDBUnit treats sheets with names starting with "=" as expected result sheets and uses them for assertion processing.  
- Name setup sheets using the format "ModuleName.EntityName". For expected result sheets, prefix this name with "=". The module name can be omitted if the entity name is unique across the entire application.  
- MxDBUnit treats sheets with names starting with characters other than "#" or "=" as setup data and uses them to load setup data.
- Each sheet should contain setup data or expected data for a single entity.  
- The first row of the sheet must list the entity's attribute names as headers (this is case-sensitive).  
- Enter a logical, unique ID (one that is intuitive to you) in the "Id" column. When loading entities, MxDBUnit builds an internal mapping between the logical IDs used in Excel and the physical IDs used in the database. This allows it to automatically convert logical IDs listed in association columns on subsequent sheets into physical IDs, thereby establishing entity associations. Therefore, please arrange your setup data sheets so that the entity being referenced by an association appears to the left of the referencing entity.  
- If you need to assert records added by the logic under test in an expected result sheet, append an "*" to the header column name(s) used to identify the record. You may specify multiple columns.  
- Additionally, for records added by the logic under test, leave the "Id" column blank in the expected result sheet.
- If the text following the "=" sign in the "Expected Results" sheet does not correspond to a persistent entity, the **AssertAllByExcel** action skips that sheet while issuing a warning. In contrast, the **AssertListByExcel** action performs the assertion as long as the sheet name matches, even if it is not an entity name.
### Additional rules for external DB testing:
- For external DB tests, name the preparation sheet using the format "TwoWaySQL module's external data source name|table name" (e.g., "EXTDS|EMPLOYEE"). For the expected results sheet, prefix that name with "=".
- Unlike internal DBs, the "Id" column holds no special significance for external DBs. Therefore, you must include an "*" in the column name of the key column within the expected results sheet. If a key column is not specified, assertions will not be performed correctly.
- A single test can handle both internal and external databases.
- When testing an external DB, you must create a "TearDown" Microflow in the module containing the test case's Microflow and call the "TearDownMxDBUnit" action. This is a mandatory requirement to prevent deadlocks.

# Restrictions
### Currently, entity names of 30 characters or fewer are supported.  
There are plans to improve this using an alias definition sheet.
### Many-to-many associations are not supported.  
There are currently no plans to support them.
### Currently, only Excel format is supported for test data.  
If there is demand, we will also support comparable text-based formats such as JSON/YAML.  
That said, it is also possible to check for differences in Excel using tools like TortoiseGit.
### Because the objects are converted to strings for comparison using java-diff-utils, the (-) and (+) indicators may sometimes feel counterintuitive.
# Dependencies
### CommunityCommons Module
### UnitTesting Module
### TwoWaySQL Module v2.7.0 or later  (Only when testing an external database) 
### poi-ooxml
### java-diff-utils
