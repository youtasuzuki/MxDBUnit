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
# Rules for Test Data Excel Files
### There are a few simple rules.  
- MxDBUnit treats sheets with names starting with "#" as documentation and ignores them.  
- MxDBUnit treats sheets with names starting with "=" as expected result sheets and uses them for assertion processing.  
- Name setup sheets using the format "ModuleName.EntityName". For expected result sheets, prefix this name with "=". The module name can be omitted if the entity name is unique across the entire application.  
- MxDBUnit treats sheets with names starting with characters other than "#" or "=" as setup data and uses them to load setup data.
- Each sheet should contain setup data or expected data for a single entity.  
- The first row of the sheet must list the entity's attribute names as headers (this is case-sensitive).  
- Enter a logical, unique ID (one that is intuitive to you) in the "Id" column. When loading entities, MxDBUnit builds an internal mapping between the logical IDs used in Excel and the physical IDs used in the database. This allows it to automatically convert logical IDs listed in association columns on subsequent sheets into physical IDs, thereby establishing entity associations. Therefore, please arrange your setup data sheets so that the entity being referenced by an association appears to the left of the referencing entity.  
- If you need to assert records added by the logic under test in an expected result sheet, append an "*" to the header column name(s) used to identify the record. You may specify multiple columns.  
- Additionally, for records added by the logic under test, leave the "Id" column blank in the expected result sheet.

# Restrictions
### Currently, entity names of 30 characters or fewer are supported.  
There are plans to improve this using an alias definition sheet.
### Many-to-many associations are not supported.  
There are currently no plans to support them.
### Currently, only Excel format is supported for test data.  
If there is demand, we will also support comparable text-based formats such as JSON/YAML.  
That said, it is also possible to check for differences in Excel using tools like TortoiseGit.
### Comparison results for entities with a large number of items can sometimes be difficult to read.  
There are plans to improve this by inserting markers to indicate the differences. In the meantime, if you have trouble identifying which fields differ, please try using generative AI to check them.
# Dependencies
### CommunityCommons Module
### UnitTesting Module
### poi-ooxml
### java-diff-utils
